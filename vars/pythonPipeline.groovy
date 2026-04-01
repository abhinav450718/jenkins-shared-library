
def call(Map config = [:]) {

    // ── Config Defaults ──────────────────────────────────────────────────────
    def repoUrl           = config.repoUrl           ?: error('repoUrl is required')
    def branch            = config.branch            ?: 'main'
    def gitCredentialsId  = config.gitCredentialsId  ?: ''
    def sonarProjectKey   = config.sonarProjectKey   ?: 'notification-worker'
    def sonarProjectName  = config.sonarProjectName  ?: 'Notification Worker'
    def sonarServer       = config.sonarServer       ?: 'SonarQube'        // Jenkins SonarQube config name
    def slackChannel      = config.slackChannel      ?: '#ci-operation-notifications'
    def coverageThreshold = config.coverageThreshold ?: '70'
    def trivySeverity     = config.trivySeverity     ?: 'HIGH,CRITICAL'
    def trivyFailOnVuln   = config.trivyFailOnVuln   ?: false
    def zapTargetUrl      = config.zapTargetUrl      ?: 'http://localhost:8000'
    def zapDockerImage    = config.zapDockerImage    ?: 'ghcr.io/zaproxy/zaproxy:stable'
    def zapFailOnAlert    = config.zapFailOnAlert    ?: false
    def REPORT_DIR        = 'reports'

    // ── Pipeline ─────────────────────────────────────────────────────────────
    try {

        // ── Stage 1: Clean Workspace ─────────────────────────────────────────
        stage('Clean Workspace') {
            cleanWs()
        }

        // ── Stage 2: Checkout Code ───────────────────────────────────────────
        stage('Checkout Code') {
            if (gitCredentialsId) {
                git branch: branch, url: repoUrl, credentialsId: gitCredentialsId
            } else {
                git branch: branch, url: repoUrl
            }
            sh 'git log --oneline -5'
        }

        // ── Stage 3: Unit Test (pytest) ──────────────────────────────────────
        stage('Unit Test') {
            sh """
                set -e
                mkdir -p ${REPORT_DIR}

                pip install --upgrade pip --quiet
                pip install -r requirements.txt --quiet
                pip install pytest pytest-cov --quiet

                pytest tests/ \
                    --cov=. \
                    --cov-report=xml:${REPORT_DIR}/coverage.xml \
                    --cov-report=html:${REPORT_DIR}/coverage-html \
                    --cov-fail-under=${coverageThreshold} \
                    --junitxml=${REPORT_DIR}/test-results.xml \
                    -v
            """

            junit allowEmptyResults: true,
                  testResults: "${REPORT_DIR}/test-results.xml"

            publishHTML(target: [
                allowMissing         : true,
                alwaysLinkToLastBuild: true,
                keepAll              : true,
                reportDir            : "${REPORT_DIR}/coverage-html",
                reportFiles          : 'index.html',
                reportName           : 'Pytest Coverage Report'
            ])
        }

        // ── Stage 4: Bug Analysis (pylint) ───────────────────────────────────
        stage('Bug Analysis') {
            sh """
                set -e
                pip install pylint --quiet

                python3 -m pylint \$(find . -name "*.py" \
                    ! -path "./tests/*"  \
                    ! -path "./.venv/*"  \
                    ! -path "./venv/*") \
                    --output-format=parseable \
                    --exit-zero \
                    > ${REPORT_DIR}/pylint-report.txt || true

                echo "--- pylint report preview (first 50 lines) ---"
                head -50 ${REPORT_DIR}/pylint-report.txt || true
            """
        }

        // ── Stage 5: Static Code Analysis (SonarQube + Quality Gate) ─────────
        stage('Static Code Analysis') {
            withSonarQubeEnv(sonarServer) {
                sh """
                    set -e
                    sonar-scanner \
                        -Dsonar.projectKey=${sonarProjectKey} \
                        -Dsonar.projectName="${sonarProjectName}" \
                        -Dsonar.sources=. \
                        -Dsonar.exclusions="**/tests/**,**/__pycache__/**,**/venv/**,**/.venv/**" \
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

        // ── Stage 6: Dependency Scan (Trivy) ─────────────────────────────────
        stage('Dependency Scan') {
            sh """
                set -e
                if ! command -v trivy > /dev/null 2>&1; then
                    echo "==> Installing Trivy..."
                    curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh \
                        | sh -s -- -b /usr/local/bin
                fi
                trivy --version

                # JSON report (full detail)
                trivy fs . \
                    --format json \
                    --output ${REPORT_DIR}/trivy-report.json \
                    --severity ${trivySeverity} \
                    --exit-code 0

                # Human-readable table (preview in console)
                trivy fs . \
                    --format table \
                    --severity ${trivySeverity} \
                    --exit-code 0 \
                    | tee ${REPORT_DIR}/trivy-summary.txt
            """

            // Optional hard-fail if critical vulns found
            if (trivyFailOnVuln) {
                def exitCode = sh(
                    script: """
                        trivy fs . \
                            --severity ${trivySeverity} \
                            --exit-code 1 \
                            --quiet
                    """,
                    returnStatus: true
                )
                if (exitCode != 0) {
                    error "Trivy found vulnerabilities with severity ${trivySeverity}. Build FAILED."
                }
            }
        }

        // ── Stage 7: DAST Scan (OWASP ZAP) ───────────────────────────────────
        stage('DAST Scan') {
            sh "mkdir -p ${REPORT_DIR}/zap"

            // Pull ZAP image
            sh "docker pull ${zapDockerImage}"

            def zapExit = sh(
                script: """
                    docker run --rm \
                        --network host \
                        -v \$(pwd)/${REPORT_DIR}/zap:/zap/wrk/:rw \
                        ${zapDockerImage} \
                        zap-baseline.py \
                            -t ${zapTargetUrl} \
                            -r zap-report.html \
                            -J zap-report.json \
                            -x zap-report.xml \
                            -l PASS \
                            -I
                """,
                returnStatus: true
            )

            publishHTML(target: [
                allowMissing         : true,
                alwaysLinkToLastBuild: true,
                keepAll              : true,
                reportDir            : "${REPORT_DIR}/zap",
                reportFiles          : 'zap-report.html',
                reportName           : 'OWASP ZAP DAST Report'
            ])

            if (zapFailOnAlert && zapExit > 0) {
                error "OWASP ZAP found alerts above threshold. Build FAILED."
            } else if (zapExit > 0) {
                unstable "OWASP ZAP reported alerts — build marked UNSTABLE. Review ZAP report."
            }
        }

        // ── Stage 8: Archive Reports ──────────────────────────────────────────
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
        // ── Post: Slack Notification + Cleanup ───────────────────────────────
        stage('Post Actions') {
            def status = currentBuild.result ?: 'FAILURE'
            def color  = (status == 'SUCCESS') ? 'good'   :
                         (status == 'UNSTABLE') ? 'warning' : 'danger'
            def emoji  = (status == 'SUCCESS') ? '✅'     :
                         (status == 'UNSTABLE') ? '⚠️'    : '❌'

            try {
                slackSend(
                    channel: slackChannel,
                    color  : color,
                    message: """\
${emoji} *${status}* — Notification Worker CI
*Job*    : ${env.JOB_NAME}
*Branch* : ${branch}
*Build*  : #${env.BUILD_NUMBER}
*URL*    : ${env.BUILD_URL}""".stripIndent()
                )
            } catch (slackErr) {
                echo "Slack notification skipped (plugin not configured): ${slackErr.message}"
            }

            cleanWs()
        }
    }
}
