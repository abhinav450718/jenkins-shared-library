def call(Map config) {

    node {

        def repoUrl    = config.repoUrl
        def branch     = config.branch ?: 'main'
        def credId     = config.gitCredentialsId ?: ''
        def GO_VERSION = config.goVersion ?: '1.22.5'
        def slackCh    = config.slackChannel ?: '#ci-operation-notifications'
        def email      = config.email ?: ''

        def REPORT_DIR = 'reports'
        def TOOLS_DIR  = '/var/lib/jenkins/tools'
        def GO_DIR     = "${TOOLS_DIR}/go-${GO_VERSION}"

        try {

            stage('Clean Workspace') {
                commonUtils.cleanWorkspace()
            }

            stage('Checkout') {
                commonUtils.checkoutCode(repoUrl, branch, credId)
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
                """
            }

            stage('Test') {
                commonUtils.createDir(REPORT_DIR)

                sh """
                    export GOROOT=${GO_DIR}
                    export GOPATH=\$HOME/go
                    export PATH=\$GOROOT/bin:\$GOPATH/bin:\$PATH

                    go test -coverprofile=${REPORT_DIR}/coverage.out ./... \
                        2>&1 | tee ${REPORT_DIR}/test.log || true

                    go tool cover -func=${REPORT_DIR}/coverage.out \
                        | tee ${REPORT_DIR}/coverage_summary.txt || true
                """
            }

            stage('Archive') {
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
                toolName: "Go Unit Testing",
                branch: branch,
                slackChannel: slackCh,
                email: email,
                reports: [
                    "Test Log"         : "${REPORT_DIR}/test.log",
                    "Coverage Summary" : "${REPORT_DIR}/coverage_summary.txt",
                    "Coverage Report"  : "${REPORT_DIR}/coverage.out"
                ],
                attachments: "${REPORT_DIR}/**"
            )

            cleanWs()
        }
    }
}
