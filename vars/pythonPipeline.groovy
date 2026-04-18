def call(Map config = [:]) {
    node {

        def repoUrl           = config.repoUrl ?: error('repoUrl is required')
        def branch            = config.branch ?: 'main'
        def credId            = config.gitCredentialsId ?: ''
        def slackCh           = config.slackChannel ?: '#ci-operation-notifications'
        def email             = config.email ?: ''
        def coverageThreshold = config.coverageThreshold ?: '70'
        def sonarProjectKey   = config.sonarProjectKey ?: 'notification-worker'
        def sonarProjectName  = config.sonarProjectName ?: 'Notification Worker'
        def sonarServer       = config.sonarServer ?: 'SonarQube'
        def trivySeverity     = config.trivySeverity ?: 'HIGH,CRITICAL'
        def trivyFailOnVuln   = config.trivyFailOnVuln ?: false

        def REPORT_DIR = 'reports'
        def VENV_DIR   = '.venv'

        def TOOLS_DIR     = '/var/lib/jenkins/tools'
        def SONAR_VERSION = '6.2.1.4610'
        def SONAR_DIR     = "${TOOLS_DIR}/sonar-scanner-${SONAR_VERSION}"
        def SONAR_BIN     = "${SONAR_DIR}/bin/sonar-scanner"

        try {

            stage('Clean Workspace') {
                commonUtils.cleanWorkspace()
            }

            stage('Checkout Code') {
                commonUtils.checkoutCode(repoUrl, branch, credId)
            }

            stage('Unit Test') {
                commonUtils.createDir(REPORT_DIR)

                sh """
                    set -e
                    python3 -m venv ${VENV_DIR}
                    . ${VENV_DIR}/bin/activate

                    pip install -q --upgrade pip
                    pip install -q -r requirements.txt
                    pip install -q pytest pytest-cov

                    pytest . \
                        --cov=. \
                        --cov-report=xml:${REPORT_DIR}/coverage.xml \
                        --junitxml=${REPORT_DIR}/test-results.xml \
                        -v 2>&1 | tee ${REPORT_DIR}/test.log || true

                    deactivate
                """

                junit allowEmptyResults: true,
                      testResults: "${REPORT_DIR}/test-results.xml"

                recordCoverage(
                    tools: [[parser: 'COBERTURA', pattern: "${REPORT_DIR}/coverage.xml"]],
                    skipPublishingChecks: true,
                    ignoreParsingErrors: true
                )
            }

            stage('Bug Analysis') {
                sh """
                    . ${VENV_DIR}/bin/activate
                    pip install -q pylint

                    pylint \$(find . -name "*.py" ! -path "./${VENV_DIR}/*") \
                        --output-format=parseable \
                        --exit-zero \
                        > ${REPORT_DIR}/pylint-report.txt || true

                    deactivate
                """
            }

            stage('Static Code Analysis') {
                withSonarQubeEnv(sonarServer) {
                    sh """
                        ${SONAR_BIN} \
                        -Dsonar.projectKey=${sonarProjectKey} \
                        -Dsonar.projectName="${sonarProjectName}" \
                        -Dsonar.sources=. \
                        -Dsonar.python.coverage.reportPaths=${REPORT_DIR}/coverage.xml \
                        -Dsonar.python.pylint.reportPaths=${REPORT_DIR}/pylint-report.txt
                    """
                }
            }

            stage('Dependency Scan') {
                sh """
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
                commonUtils.archiveReports(REPORT_DIR)
            }

            currentBuild.result = 'SUCCESS'

        } catch (err) {
            currentBuild.result = 'FAILURE'
            throw err

        } finally {

            def status = currentBuild.result ?: 'FAILURE'

            commonUtils.notifyBuild(
                status: status,
                toolName: "Python CI Pipeline",
                branch: branch,
                slackChannel: slackCh,
                email: email,
                reports: [
                    "Test Results"   : "${REPORT_DIR}/test-results.xml",
                    "Coverage"       : "${REPORT_DIR}/coverage.xml",
                    "Pylint Report"  : "${REPORT_DIR}/pylint-report.txt",
                    "Trivy Summary"  : "${REPORT_DIR}/trivy-summary.txt",
                    "Trivy Report"   : "${REPORT_DIR}/trivy-report.json"
                ],
                attachments: "${REPORT_DIR}/**"
            )

            cleanWs()
        }
    }
}
