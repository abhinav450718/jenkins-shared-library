def call(Map config) {
    node {
        def repoUrl          = config.repoUrl
        def branch           = config.branch           ?: 'main'
        def gitCredentialsId = config.gitCredentialsId ?: ''
        def sonarProjectKey  = config.sonarProjectKey  ?: 'employee-api'
        def sonarProjectName = config.sonarProjectName ?: 'Employee API'
        def slackChannel     = config.slackChannel     ?: '#ci-operation-notifications'
        def email            = config.email            ?: ''
        def GO_VERSION       = config.goVersion        ?: '1.22.5'
        def SONAR_VERSION    = '5.0.1.3006'
        def REPORT_DIR       = 'reports'

        def TOOLS_DIR = '/var/lib/jenkins/tools'
        def GO_DIR    = "${TOOLS_DIR}/go-${GO_VERSION}"
        def SONAR_DIR = "${TOOLS_DIR}/sonar-scanner-${SONAR_VERSION}"

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
            }

            stage('Setup Go') {
                sh """
                    set -e
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
                    set -e
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
                sh "mkdir -p ${REPORT_DIR}"
            }

            stage('Verify SonarQube Reachable') {
                sh """
                    curl -sf http://192.168.8.17:9000/api/system/status | grep -q UP || (exit 1)
                """
            }

            stage('Generate Coverage For Sonar') {
                sh """
                    set -e
                    export GOROOT=${GO_DIR}
                    export GOPATH=\$HOME/go
                    export PATH=\$GOROOT/bin:\$GOPATH/bin:\$PATH

                    go test -v \
                        -covermode=atomic \
                        -coverprofile="${REPORT_DIR}/coverage.out" \
                        ./api/... ./client/... ./config/... ./middleware/... ./routes/... \
                        2>&1 | tee "${REPORT_DIR}/test.log" || true

                    go tool cover -func="${REPORT_DIR}/coverage.out" \
                        | tee "${REPORT_DIR}/coverage_summary.txt" || true
                """
            }

            stage('SonarQube Analysis') {
                withSonarQubeEnv('SonarQube') {
                    sh """
                        set -e
                        export PATH=${SONAR_DIR}/bin:\$PATH

                        sonar-scanner \
                            -Dsonar.projectKey=${sonarProjectKey} \
                            -Dsonar.projectName="${sonarProjectName}" \
                            -Dsonar.projectVersion=1.0 \
                            -Dsonar.sources=. \
                            -Dsonar.language=go \
                            -Dsonar.go.coverage.reportPaths="${REPORT_DIR}/coverage.out" \
                            -Dsonar.exclusions=**/vendor/**,**/docs/**,**/reports/**,**/*.md,**/*_test.go \
                            -Dsonar.test.inclusions=**/*_test.go \
                            -Dsonar.sourceEncoding=UTF-8 \
                            2>&1 | tee "${REPORT_DIR}/sonar.log"
                    """
                }
            }

            stage('Archive Reports') {
                archiveArtifacts artifacts: "${REPORT_DIR}/**", fingerprint: true
            }

            currentBuild.result = 'SUCCESS'

        } catch (err) {
            currentBuild.result = 'FAILURE'
            throw err

        } finally {
            stage('Post Actions') {
                def status          = currentBuild.result ?: 'FAILURE'
                def testLog         = "${env.BUILD_URL}artifact/${REPORT_DIR}/test.log"
                def coverageSummary = "${env.BUILD_URL}artifact/${REPORT_DIR}/coverage_summary.txt"
                def coverageOut     = "${env.BUILD_URL}artifact/${REPORT_DIR}/coverage.out"
                def sonarLog        = "${env.BUILD_URL}artifact/${REPORT_DIR}/sonar.log"

                // Slack
                slackSend(
                    channel: slackChannel,
                    color: (status == 'SUCCESS') ? 'good' : 'danger',
                    message: "*${status}* - Go SonarQube Analysis\n" +
                             "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                             "*Job Name:*       ${env.JOB_NAME}\n" +
                             "*Build Number:*   #${env.BUILD_NUMBER}\n" +
                             "*Branch:*         ${branch}\n" +
                             "*Status:*         ${status}\n" +
                             "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                             "<${env.BUILD_URL}|View Build>   |   " +
                             "<${testLog}|Test Log>   |   " +
                             "<${coverageSummary}|Coverage Summary>   |   " +
                             "<${coverageOut}|Coverage Report>   |   " +
                             "<${sonarLog}|Sonar Log>"
                )

                // Email
                if (email) {
                    emailext(
                        to: email,
                        subject: "${status}: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                        body: """
                        <h3>${status} - Go SonarQube Analysis</h3>
                        <p><b>Job Name:</b> ${env.JOB_NAME}</p>
                        <p><b>Build Number:</b> #${env.BUILD_NUMBER}</p>
                        <p><b>Branch:</b> ${branch}</p>
                        <p><b>Status:</b> ${status}</p>
                        <p>
                            <a href="${env.BUILD_URL}">View Build</a> |
                            <a href="${testLog}">Test Log</a> |
                            <a href="${coverageSummary}">Coverage Summary</a> |
                            <a href="${coverageOut}">Coverage Report</a> |
                            <a href="${sonarLog}">Sonar Log</a>
                        </p>
                        """,
                        mimeType: 'text/html',
                        attachmentsPattern: "${REPORT_DIR}/**"
                    )
                }

                cleanWs()
            }
        }
    }
}
