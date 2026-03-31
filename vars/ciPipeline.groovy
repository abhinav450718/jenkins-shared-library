def call(Map config) {
    node {
        def repoUrl           = config.repoUrl
        def branch            = config.branch            ?: 'main'
        def gitCredentialsId  = config.gitCredentialsId  ?: ''
        def sonarProjectKey   = config.sonarProjectKey   ?: 'notification-worker'
        def sonarProjectName  = config.sonarProjectName  ?: 'Notification Worker'
        def slackChannel      = config.slackChannel      ?: '#ci-operation-notifications'
        def coverageThreshold = config.coverageThreshold ?: '70'
        def trivySeverity     = config.trivySeverity     ?: 'HIGH,CRITICAL'
        def REPORT_DIR        = 'reports'

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

            stage('Bug Analysis') {
                sh """
                    set -e
                    pip install pylint --quiet

                    python3 -m pylint \$(find . -name "*.py" \
                        ! -path "./tests/*" \
                        ! -path "./.venv/*" \
                        ! -path "./venv/*") \
                        --output-format=parseable \
                        --exit-zero \
                        > ${REPORT_DIR}/pylint-report.txt || true

                    echo "--- pylint report preview ---"
                    head -50 ${REPORT_DIR}/pylint-report.txt || true
                """
            }

            stage('Static Code Analysis') {
                withSonarQubeEnv('SonarQube') {
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

            stage('Dependency Scan') {
                sh """
                    set -e
                    if ! command -v trivy > /dev/null 2>&1; then
                        echo "==> Installing Trivy..."
                        curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh \
                            | sh -s -- -b /usr/local/bin
                    fi
                    trivy --version

                    trivy fs . \
                        --format json \
                        --output ${REPORT_DIR}/trivy-report.json \
                        --severity ${trivySeverity} \
                        --exit-code 0

                    trivy fs . \
                        --format table \
                        --severity ${trivySeverity} \
                        --exit-code 0 \
                        | tee ${REPORT_DIR}/trivy-summary.txt
                """
            }

            stage('Archive Reports') {
                archiveArtifacts artifacts: "${REPORT_DIR}/**", fingerprint: true
            }

            currentBuild.result = 'SUCCESS'

        } catch (err) {
            currentBuild.result = 'FAILURE'
            echo "Pipeline error: ${err}"
            throw err

        } finally {
            stage('Post Actions') {
                def status = currentBuild.result ?: 'FAILURE'
                def color  = (status == 'SUCCESS') ? 'good'  : 'danger'
                def emoji  = (status == 'SUCCESS') ? '✅'    : '❌'
                slackSend(
                    channel: slackChannel,
                    color  : color,
                    message: """\
${emoji} *${status}* - Python Pipeline | ${sonarProjectName}
*Job*    : ${env.JOB_NAME}
*Branch* : ${branch}
*Build*  : #${env.BUILD_NUMBER}
*URL*    : ${env.BUILD_URL}""".stripIndent()
                )
                cleanWs()
            }
        }
    }
}
