/**
 * Shared Library Step: pythonPipeline
 * File: vars/pythonPipeline.groovy
 *
 * CI pipeline for notification-worker (Python):
 *   1. Clean Workspace
 *   2. Checkout Code
 *   3. Unit Test        -> pytest + pytest-cov
 *   4. Bug Analysis     -> pylint
 *   5. Dependency Scan  -> Trivy
 *   6. Archive Reports
 *   Post: Slack notification + cleanWs
 */
def call(Map config = [:]) {

    // Config Defaults
    def repoUrl           = config.repoUrl          ?: error('repoUrl is required')
    def branch            = config.branch           ?: 'main'
    def gitCredentialsId  = config.gitCredentialsId ?: ''
    def slackChannel      = config.slackChannel     ?: '#ci-operation-notifications'
    def coverageThreshold = config.coverageThreshold?: '70'
    def trivySeverity     = config.trivySeverity    ?: 'HIGH,CRITICAL'
    def trivyFailOnVuln   = config.trivyFailOnVuln  ?: false
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

                pytest tests/ \
                    --cov=. \
                    --cov-report=xml:${REPORT_DIR}/coverage.xml \
                    --cov-report=html:${REPORT_DIR}/coverage-html \
                    --cov-fail-under=${coverageThreshold} \
                    --junitxml=${REPORT_DIR}/test-results.xml \
                    -v

                deactivate
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

        // Stage 4: Bug Analysis (pylint)
        stage('Bug Analysis') {
            sh """
                set -e
                # Reuse the venv from Unit Test stage
                . ${VENV_DIR}/bin/activate

                pip install pylint --quiet

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
            """

            archiveArtifacts artifacts: "${REPORT_DIR}/pylint-report.txt",
                             allowEmptyArchive: true
        }

        // Stage 5: Dependency Scan (Trivy)
        stage('Dependency Scan') {
            sh """
                set -e
                if ! command -v trivy > /dev/null 2>&1; then
                    echo "==> Installing Trivy..."
                    curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh \
                        | sh -s -- -b /usr/local/bin
                fi
                trivy --version

                # Full JSON report
                trivy fs . \
                    --format json \
                    --output ${REPORT_DIR}/trivy-report.json \
                    --severity ${trivySeverity} \
                    --exit-code 0

                # Human-readable table in console + saved to file
                trivy fs . \
                    --format table \
                    --severity ${trivySeverity} \
                    --exit-code 0 \
                    | tee ${REPORT_DIR}/trivy-summary.txt
            """

            if (trivyFailOnVuln) {
                def exitCode = sh(
                    script: "trivy fs . --severity ${trivySeverity} --exit-code 1 --quiet",
                    returnStatus: true
                )
                if (exitCode != 0) {
                    error "Trivy found vulnerabilities with severity ${trivySeverity}. Build FAILED."
                }
            }

            archiveArtifacts artifacts: "${REPORT_DIR}/trivy-report.json, ${REPORT_DIR}/trivy-summary.txt",
                             allowEmptyArchive: true
        }

        // Stage 6: Archive All Reports
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

            // Slack bold (*text*) must NOT start a line in Groovy triple-quoted
            // strings — parser treats leading * as multiplication and crashes.
            // Safe fix: use plain string concatenation.
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
