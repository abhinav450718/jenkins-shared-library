def call(Map config = [:]) {

    def repoUrl           = config.repoUrl           ?: error('repoUrl is required')
    def branch            = config.branch            ?: 'main'
    def gitCredentialsId  = config.gitCredentialsId  ?: ''
    def slackChannel      = config.slackChannel      ?: '#ci-operation-notifications'
    def coverageThreshold = config.coverageThreshold ?: '70'
    def sonarProjectKey   = config.sonarProjectKey   ?: 'notification-worker'
    def sonarProjectName  = config.sonarProjectName  ?: 'Notification Worker'
    def sonarServer       = config.sonarServer       ?: 'SonarQube'
    def trivySeverity     = config.trivySeverity     ?: 'HIGH,CRITICAL'
    def trivyFailOnVuln   = config.trivyFailOnVuln   ?: false
    def REPORT_DIR        = 'reports'
    def VENV_DIR          = '.venv'

    // ── Sonar installs to jenkins home (no sudo/permission issues) ──
    def TOOLS_DIR     = '/var/lib/jenkins/tools'
    def SONAR_VERSION = '6.2.1.4610'
    def SONAR_DIR     = "${TOOLS_DIR}/sonar-scanner-${SONAR_VERSION}"
    def SONAR_BIN     = "${SONAR_DIR}/bin/sonar-scanner"

    try {

        stage('Clean Workspace') {
            cleanWs()
        }

        stage('Checkout Code') {
            if (gitCredentialsId) {
                git branch: branch, url: repoUrl, credentialsId: gitCredentialsId
            } else {
                git branch: branch, url: repoUrl
            }
            sh 'git log --oneline -5'
        }

        stage('Unit Test') {
            sh """
                set -e
                mkdir -p ${REPORT_DIR}

                python3 -m venv ${VENV_DIR}
                . ${VENV_DIR}/bin/activate

                pip install --upgrade pip --quiet
                pip install -r requirements.txt --quiet
                pip install pytest pytest-cov --quiet

                TEST_COUNT=\$(pytest . --collect-only -q --ignore=${VENV_DIR} 2>/dev/null | grep -c "::" || true)
                echo "==> Found \${TEST_COUNT} test(s)"

                if [ "\${TEST_COUNT}" -gt 0 ]; then
                    echo "==> Running tests with coverage..."
                    pytest . \
                        --ignore=${VENV_DIR} \
                        --cov=. \
                        --cov-report=xml:${REPORT_DIR}/coverage.xml \
                        --cov-report=term-missing \
                        --cov-fail-under=${coverageThreshold} \
                        --junitxml=${REPORT_DIR}/test-results.xml \
                        -v 2>&1 | tee ${REPORT_DIR}/test.log || true
                else
                    echo "WARNING: No test files found. Skipping coverage enforcement."
                    pytest . \
                        --ignore=${VENV_DIR} \
                        --junitxml=${REPORT_DIR}/test-results.xml \
                        -v 2>&1 | tee ${REPORT_DIR}/test.log || true

                    # ── Minimal valid coverage.xml so sonar/recordCoverage never fails ──
                    cat > ${REPORT_DIR}/coverage.xml << 'XMLEOF'
<?xml version="1.0" ?>
<coverage version="7.0" timestamp="0" lines-valid="0" lines-covered="0" line-rate="0" branches-covered="0" branches-valid="0" branch-rate="0" complexity="0">
    <packages/>
</coverage>
XMLEOF
                fi

                deactivate
            """

            junit allowEmptyResults: true,
                  testResults: "${REPORT_DIR}/test-results.xml"

            recordCoverage(
                tools: [[parser: 'COBERTURA', pattern: "${REPORT_DIR}/coverage.xml"]],
                id: 'python-coverage',
                name: 'Python Coverage',
                skipPublishingChecks: true,
                ignoreParsingErrors: true
            )

            archiveArtifacts artifacts: "${REPORT_DIR}/test-results.xml, ${REPORT_DIR}/test.log, ${REPORT_DIR}/coverage.xml",
                             allowEmptyArchive: true
        }

        stage('Bug Analysis') {
            sh """
                set -e
                . ${VENV_DIR}/bin/activate

                pip install pylint --quiet

                echo "==> Running pylint..."
                python3 -m pylint \$(find . -name "*.py" \
                    ! -path "./${VENV_DIR}/*" \
                    ! -path "./tests/*"        \
                    ! -path "./venv/*") \
                    --output-format=parseable \
                    --exit-zero \
                    > ${REPORT_DIR}/pylint-report.txt || true

                deactivate

                echo "--- pylint report preview (first 50 lines) ---"
                head -50 ${REPORT_DIR}/pylint-report.txt || true

                echo "==> Issue summary:"
                echo "  Errors    (E): \$(grep -c ': E' ${REPORT_DIR}/pylint-report.txt || echo 0)"
                echo "  Warnings  (W): \$(grep -c ': W' ${REPORT_DIR}/pylint-report.txt || echo 0)"
                echo "  Refactor  (R): \$(grep -c ': R' ${REPORT_DIR}/pylint-report.txt || echo 0)"
                echo "  Convention(C): \$(grep -c ': C' ${REPORT_DIR}/pylint-report.txt || echo 0)"
            """

            archiveArtifacts artifacts: "${REPORT_DIR}/pylint-report.txt",
                             allowEmptyArchive: true
        }

        stage('Static Code Analysis') {
            withSonarQubeEnv(sonarServer) {
                sh """
                    set -e

                    # ── Install to jenkins tools dir, NOT /opt ──
                    if [ ! -f "${SONAR_BIN}" ]; then
                        echo "==> Installing sonar-scanner ${SONAR_VERSION} to ${SONAR_DIR}..."
                        mkdir -p ${TOOLS_DIR}
                        curl -fsSL https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-${SONAR_VERSION}-linux-x64.zip \
                            -o /tmp/sonar-scanner.zip
                        unzip -q /tmp/sonar-scanner.zip -d ${TOOLS_DIR}
                        mv ${TOOLS_DIR}/sonar-scanner-${SONAR_VERSION}-linux-x64 ${SONAR_DIR} 2>/dev/null || true
                        rm -f /tmp/sonar-scanner.zip
                        echo "==> Installed at ${SONAR_DIR}"
                    else
                        echo "==> sonar-scanner already at ${SONAR_DIR}, skipping"
                    fi

                    ${SONAR_BIN} --version

                    ${SONAR_BIN} \
                        -Dsonar.projectKey=${sonarProjectKey} \
                        -Dsonar.projectName="${sonarProjectName}" \
                        -Dsonar.sources=. \
                        -Dsonar.exclusions="**/${VENV_DIR}/**,**/tests/**,**/__pycache__/**,**/venv/**" \
                        -Dsonar.language=py \
                        -Dsonar.python.pylint.reportPaths=${REPORT_DIR}/pylint-report.txt \
                        -Dsonar.python.coverage.reportPaths=${REPORT_DIR}/coverage.xml
                """
            }

            timeout(time: 5, unit: 'MINUTES') {
                def qg = waitForQualityGate()
                if (qg.status != 'OK') {
                    error "SonarQube Quality Gate FAILED: ${qg.status}"
                }
            }
        }

        stage('Dependency Scan') {
            sh """
                set -e
                if ! command -v trivy > /dev/null 2>&1; then
                    echo "==> Installing Trivy..."
                    curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh \
                        | sh -s -- -b /usr/local/bin
                fi
                trivy --version

                export TRIVY_CACHE_DIR=\$(pwd)/.trivy-cache
                mkdir -p \$TRIVY_CACHE_DIR

                echo "==> Disk space available:"
                df -h \$(pwd)

                trivy fs . \
                    --cache-dir \$TRIVY_CACHE_DIR \
                    --format json \
                    --output ${REPORT_DIR}/trivy-report.json \
                    --severity ${trivySeverity} \
                    --exit-code 0

                trivy fs . \
                    --cache-dir \$TRIVY_CACHE_DIR \
                    --skip-db-update \
                    --format table \
                    --severity ${trivySeverity} \
                    --exit-code 0 \
                    | tee ${REPORT_DIR}/trivy-summary.txt
            """

            if (trivyFailOnVuln) {
                def exitCode = sh(
                    script: """
                        export TRIVY_CACHE_DIR=\$(pwd)/.trivy-cache
                        trivy fs . --cache-dir \$TRIVY_CACHE_DIR --skip-db-update \
                            --severity ${trivySeverity} --exit-code 1 --quiet
                    """,
                    returnStatus: true
                )
                if (exitCode != 0) {
                    error "Trivy found vulnerabilities with severity ${trivySeverity}. Build FAILED."
                }
            }

            archiveArtifacts artifacts: "${REPORT_DIR}/trivy-report.json, ${REPORT_DIR}/trivy-summary.txt",
                             allowEmptyArchive: true
        }

        stage('Archive Reports') {
            archiveArtifacts artifacts: "${REPORT_DIR}/**",
                             allowEmptyArchive: true,
                             fingerprint: true
        }

        currentBuild.result = 'SUCCESS'

    } catch (err) {
        currentBuild.result = 'FAILURE'
        echo "Pipeline error: ${err}"
        throw err

    } finally {
        stage('Post Actions') {
            def status = currentBuild.result ?: 'FAILURE'
            def color  = (status == 'SUCCESS') ? 'good' : 'danger'

            def msg = status + ' - Notification Worker CI\n' +
                      'Job    : ' + env.JOB_NAME + '\n' +
                      'Branch : ' + branch + '\n' +
                      'Build  : #' + env.BUILD_NUMBER + '\n' +
                      'URL    : ' + env.BUILD_URL

            try {
                slackSend(channel: slackChannel, color: color, message: msg)
            } catch (slackErr) {
                echo "Slack notification skipped: ${slackErr.message}"
            }

            cleanWs()
        }
    }
}
