def call(Map config) {

    node {

        def repoUrl      = config.repoUrl
        def branch       = config.branch ?: 'main'
        def credId       = config.gitCredentialsId ?: ''
        def slackCh      = config.slackChannel ?: '#ci-operation-notifications'
        def email        = config.email ?: ''
        def GO_VERSION   = config.goVersion ?: '1.22.5'
        def sonarKey     = config.sonarProjectKey ?: 'employee-api'
        def sonarName    = config.sonarProjectName ?: 'Employee API'

        def SONAR_VERSION = '5.0.1.3006'
        def REPORT_DIR    = 'reports'

        def TOOLS_DIR = '/var/lib/jenkins/tools'
        def GO_DIR    = "${TOOLS_DIR}/go-${GO_VERSION}"
        def SONAR_DIR = "${TOOLS_DIR}/sonar-scanner-${SONAR_VERSION}"

        try {

            stage('Clean Workspace') {
                commonUtils.cleanWorkspace()
            }

            stage('Checkout Code') {
                commonUtils.checkoutCode(repoUrl, branch, credId)
            }

            stage('Setup Go') {
                sh """
                    if [ ! -f "${GO_DIR}/bin/go" ]; then
                        mkdir -p ${TOOLS_DIR}
                        curl -sLO https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz
                        tar -xzf go${GO_VERSION}.linux-amd64.tar.gz
                        mv go ${GO_DIR}
                        rm -f go${GO_VERSION}.linux-amd64.tar.gz
                    fi
                    ${GO_DIR}/bin/go version
                """
            }

            stage('Setup SonarScanner') {
                sh """
                    if [ ! -f "${SONAR_DIR}/bin/sonar-scanner" ]; then
                        mkdir -p ${TOOLS_DIR}
                        curl -sLO https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-${SONAR_VERSION}-linux.zip
                        unzip -q sonar-scanner-cli-${SONAR_VERSION}-linux.zip -d ${TOOLS_DIR}
                        mv ${TOOLS_DIR}/sonar-scanner-${SONAR_VERSION}-linux ${SONAR_DIR}
                        rm -f sonar-scanner-cli-${SONAR_VERSION}-linux.zip
                    fi
                    ${SONAR_DIR}/bin/sonar-scanner --version
                """
            }

            stage('Prepare Reports') {
                commonUtils.createDir(REPORT_DIR)
            }

            stage('Verify SonarQube') {
                sh "curl -sf http://192.168.8.17:9000/api/system/status | grep -q UP"
            }

            stage('Generate Coverage') {
                sh """
                    export GOROOT=${GO_DIR}
                    export GOPATH=\$HOME/go
                    export PATH=\$GOROOT/bin:\$GOPATH/bin:\$PATH

                    go test -covermode=atomic \
                        -coverprofile=${REPORT_DIR}/coverage.out \
                        ./... \
                        2>&1 | tee ${REPORT_DIR}/test.log || true

                    go tool cover -func=${REPORT_DIR}/coverage.out \
                        | tee ${REPORT_DIR}/coverage_summary.txt || true
                """
            }

            stage('SonarQube Analysis') {
                withSonarQubeEnv('SonarQube') {
                    sh """
                        export PATH=${SONAR_DIR}/bin:\$PATH

                        sonar-scanner \
                            -Dsonar.projectKey=${sonarKey} \
                            -Dsonar.projectName="${sonarName}" \
                            -Dsonar.sources=. \
                            -Dsonar.go.coverage.reportPaths=${REPORT_DIR}/coverage.out \
                            -Dsonar.exclusions=**/vendor/**,**/reports/** \
                            2>&1 | tee ${REPORT_DIR}/sonar.log
                    """
                }
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

            def testLog   = "${env.BUILD_URL}artifact/${REPORT_DIR}/test.log"
            def coverage  = "${env.BUILD_URL}artifact/${REPORT_DIR}/coverage_summary.txt"
            def sonarLog  = "${env.BUILD_URL}artifact/${REPORT_DIR}/sonar.log"

            def message = """
*${status}* - Go SonarQube Analysis
━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Job: ${env.JOB_NAME}
Build: #${env.BUILD_NUMBER}
Branch: ${branch}
Status: ${status}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━
<${env.BUILD_URL}|View Build> |
<${testLog}|Test Log> |
<${coverage}|Coverage Summary> |
<${sonarLog}|Sonar Log>
"""

            commonUtils.sendSlack(slackCh, message, status)

            commonUtils.sendEmail(
                email,
                "${status}: Sonar Analysis",
                """
                <h3>${status} - Go SonarQube Analysis</h3>
                <p><b>Job:</b> ${env.JOB_NAME}</p>
                <p><b>Build:</b> #${env.BUILD_NUMBER}</p>
                <p><b>Branch:</b> ${branch}</p>
                <p><b>Status:</b> ${status}</p>

                <p>
                    <a href="${env.BUILD_URL}">View Build</a> |
                    <a href="${testLog}">Test Log</a> |
                    <a href="${coverage}">Coverage Summary</a> |
                    <a href="${sonarLog}">Sonar Log</a>
                </p>
                """,
                "${REPORT_DIR}/**"
            )

            cleanWs()
        }
    }
}
