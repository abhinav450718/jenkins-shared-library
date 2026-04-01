def call(Map config = [:]) {

    // Config Defaults
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

    try {

        // Stage 1: Clean Workspace
        stage('Clean Workspace') {
            cleanWs()
        }

        // Stage 2: Checkout Code
        stage('Checkout Code') {
            if (gitCredentialsId) {
                git branch: branch, url: repoUrl, credentialsId: gitCredentialsId
            } else {
                git branch: branch, url: repoUrl
            }
            sh 'git log --oneline -5'
        }

        // Stage 3: Unit Test (pytest + pytest-cov)
        stage('Unit Test') {
            sh """
                set -e
                mkdir -p ${REPORT_DIR}

                # Python 3.12+ on Debian/Ubuntu blocks system-wide pip.
                # Use an isolated virtualenv inside the workspace.
                python3 -m venv ${VENV_DIR}
                . ${VENV_DIR}/bin/activate

                pip install --upgrade pip --quiet
                pip install -r requirements.txt --quiet
                pip install pytest pytest-cov --quiet

                # Check if any test files exist before running
                TEST_COUNT=\$(pytest . --collect-only -q --ignore=${VENV_DIR} 2>/dev/null | grep "test session starts" -A 999 | grep -c "::" || true)

                if [ "\$TEST_COUNT" -gt 0 ]; then
                    pytest . \
                        --ignore=${VENV_DIR} \
                        --cov=. \
                        --cov-report=xml:${REPORT_DIR}/coverage.xml \
                        --cov-report=html:${REPORT_DIR}/coverage-html \
                        --cov-fail-under=${coverageThreshold} \
                        --junitxml=${REPORT_DIR}/test-results.xml \
                        -v
                else
                    echo "WARNING: No test files found. Skipping coverage enforcement."
                    pytest . \
                        --ignore=${VENV_DIR} \
                        --junitxml=${REPORT_DIR}/test-results.xml \
                        -v || true
                fi

                deactivate
            """

            junit allowEmptyResults: true,
                  testResults: "${REPORT_DIR}/test-results.xml"

            // Verify coverage XML was generated (equivalent to JaCoCo verify step)
            sh """
                if [ -f ${REPORT_DIR}/coverage.xml ]; then
                    echo "Coverage report found: ${REPORT_DIR}/coverage.xml"
                else
                    echo "WARNING: coverage.xml not generated (no tests ran)."
                fi
            """

            // Publish HTML coverage report (equivalent to JaCoCo HTML report)
            publishHTML(target: [
                allowMissing         : true,
                alwaysLinkToLastBuild: true,
                keepAll              : true,
                reportDir            : "${REPORT_DIR}/coverage-html",
                reportFiles          : 'index.html',
                reportName           : 'Python Coverage Report'
            ])

            // Publish coverage trend graph in Jenkins UI
            // Equivalent to jacoco() publisher in Java pipelines
            // Requires "Coverage" plugin installed in Jenkins
            recordCoverage(
                tools: [[parser: 'COBERTURA', pattern: "${REPORT_DIR}/coverage.xml"]],
                id: 'python-coverage',
                name: 'Python Coverage',
                skipPublishingChecks: true
            )
        }

        // Stage 4: Bug Analysis (pylint)
        // Python equivalent of Java SonarQube bug/smell pre-scan using Maven
        // pylint detects: bugs, code smells, convention violations, refactor hints
        // Its parseable report is later consumed by sonar-scanner in Stage 5
        stage('Bug Analysis') {
            sh """
                set -e
                # Reuse the venv from Unit Test stage
                . ${VENV_DIR}/bin/activate

                pip install pylint --quiet

                echo "==> Running pylint bug & code quality analysis..."
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

                # Summary: count issues by type
                echo "==> Issue summary:"
                echo "  Errors   (E): \$(grep -c ': E' ${REPORT_DIR}/pylint-report.txt || echo 0)"
                echo "  Warnings (W): \$(grep -c ': W' ${REPORT_DIR}/pylint-report.txt || echo 0)"
                echo "  Refactor (R): \$(grep -c ': R' ${REPORT_DIR}/pylint-report.txt || echo 0)"
                echo "  Convention(C): \$(grep -c ': C' ${REPORT_DIR}/pylint-report.txt || echo 0)"
            """

            archiveArtifacts artifacts: "${REPORT_DIR}/pylint-report.txt",
                             allowEmptyArchive: true
        }

        // Stage 5: Static Code Analysis (SonarQube + Quality Gate)
        stage('Static Code Analysis') {
            withSonarQubeEnv(sonarServer) {
                sh """
                    set -e

                    # Auto-install sonar-scanner CLI if not present on the agent
                    if ! command -v sonar-scanner > /dev/null 2>&1; then
                        echo "==> sonar-scanner not found. Installing..."
                        SONAR_VERSION="6.2.1.4610"
                        SONAR_ZIP="sonar-scanner-cli-\${SONAR_VERSION}-linux-x64.zip"
                        SONAR_URL="https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/\${SONAR_ZIP}"

                        curl -fsSL "\${SONAR_URL}" -o /tmp/sonar-scanner.zip
                        unzip -q /tmp/sonar-scanner.zip -d /opt/
                        ln -sf /opt/sonar-scanner-\${SONAR_VERSION}-linux-x64/bin/sonar-scanner /usr/local/bin/sonar-scanner
                        rm -f /tmp/sonar-scanner.zip
                        echo "==> sonar-scanner installed successfully"
                    fi

                    sonar-scanner --version

                    sonar-scanner \
                        -Dsonar.projectKey=${sonarProjectKey} \
                        -Dsonar.projectName="${sonarProjectName}" \
                        -Dsonar.sources=. \
                        -Dsonar.exclusions="**/${VENV_DIR}/**,**/tests/**,**/__pycache__/**,**/venv/**" \
                        -Dsonar.language=py \
                        -Dsonar.python.pylint.reportPaths=${REPORT_DIR}/pylint-report.txt \
                        -Dsonar.python.coverage.reportPaths=${REPORT_DIR}/coverage.xml
                """
            }

            // Wait for SonarQube webhook to report Quality Gate result
            timeout(time: 5, unit: 'MINUTES') {
                def qg = waitForQualityGate()
                if (qg.status != 'OK') {
                    error "SonarQube Quality Gate FAILED: ${qg.status}. Check SonarQube dashboard."
                }
            }
        }

        // Stage 6: Dependency Scan (Trivy)
        stage('Dependency Scan') {
            sh """
                set -e
                if ! command -v trivy > /dev/null 2>&1; then
                    echo "==> Installing Trivy..."
                    curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh \
                        | sh -s -- -b /usr/local/bin
                fi
                trivy --version

                # Use workspace-local cache to avoid filling /var/lib/jenkins/.cache
                # and to keep it scoped per-build (auto-cleaned by cleanWs)
                export TRIVY_CACHE_DIR=\$(pwd)/.trivy-cache
                mkdir -p \$TRIVY_CACHE_DIR

                echo "==> Disk space available:"
                df -h \$(pwd)

                # Full JSON report (downloads DB on first run)
                trivy fs . \
                    --cache-dir \$TRIVY_CACHE_DIR \
                    --format json \
                    --output ${REPORT_DIR}/trivy-report.json \
                    --severity ${trivySeverity} \
                    --exit-code 0

                # Human-readable table (reuses already-downloaded DB)
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
                        trivy fs . --cache-dir \$TRIVY_CACHE_DIR --skip-db-update --severity ${trivySeverity} --exit-code 1 --quiet
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

        // Stage 7: Archive All Reports
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
