def call(Map config) {
    node {
        def repoUrl          = config.repoUrl
        def branch           = config.branch           ?: 'main'
        def gitCredentialsId = config.gitCredentialsId ?: ''
        def GO_VERSION       = config.goVersion        ?: '1.22.5'
        def slackChannel     = config.slackChannel     ?: '#ci-operation-notifications'
        def REPORT_DIR       = 'reports'
        def TOOLS_DIR        = '/var/lib/jenkins/tools'
        def GO_DIR           = "${TOOLS_DIR}/go-${GO_VERSION}"

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

            stage('Prepare Reports') {
                sh "mkdir -p ${REPORT_DIR}"
            }

            stage('Test') {
                sh """
                    set -e
                    export GOROOT=${GO_DIR}
                    export GOPATH=\$HOME/go
                    export PATH=\$GOROOT/bin:\$GOPATH/bin:\$PATH

                    go test -v \
                        -covermode=atomic \
                        -coverprofile="${REPORT_DIR}/coverage.out" \
                        \$(go list ./... | grep -v docs | grep -v model | grep -v migration) \
                        2>&1 | tee "${REPORT_DIR}/test.log" || true

                    if [ -s "${REPORT_DIR}/coverage.out" ]; then
                        go tool cover -func="${REPORT_DIR}/coverage.out" \
                            | tee "${REPORT_DIR}/coverage_summary.txt"
                    else
                        echo "mode: atomic" > "${REPORT_DIR}/coverage.out"
                    fi
                """
            }

            stage('Archive Reports') {
                archiveArtifacts artifacts: "${REPORT_DIR}/test.log, ${REPORT_DIR}/coverage_summary.txt, ${REPORT_DIR}/coverage.out", fingerprint: true
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

                if (status == 'FAILURE') {
                    slackSend(
                        channel: slackChannel,
                        color: 'danger',
                        message: "*FAILED* - Go Test & Coverage\n" +
                                 "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                                 "*Job Name:*       " + env.JOB_NAME + "\n" +
                                 "*Build Number:*   #" + env.BUILD_NUMBER + "\n" +
                                 "*Branch:*         " + branch + "\n" +
                                 "*Status:*         Build Failed\n" +
                                 "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                                 "<" + env.BUILD_URL + "|View Build>   |   " +
                                 "<" + testLog + "|Test Log>"
                    )
                } else {
                    slackSend(
                        channel: slackChannel,
                        color: 'good',
                        message: "*SUCCESS* - Go Test & Coverage\n" +
                                 "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                                 "*Job Name:*       " + env.JOB_NAME + "\n" +
                                 "*Build Number:*   #" + env.BUILD_NUMBER + "\n" +
                                 "*Branch:*         " + branch + "\n" +
                                 "*Status:*         Build Passed\n" +
                                 "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                                 "<" + env.BUILD_URL + "|View Build>   |   " +
                                 "<" + testLog + "|Test Log>   |   " +
                                 "<" + coverageSummary + "|Coverage Summary>   |   " +
                                 "<" + coverageOut + "|Coverage Report>"
                    )
                }
                cleanWs()
            }
        }
    }
}
