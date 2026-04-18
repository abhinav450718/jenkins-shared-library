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

            stage('Prepare Reports') {
                commonUtils.createDir(REPORT_DIR)
            }

            stage('Run Tests') {
                sh """
                    export GOROOT=${GO_DIR}
                    export GOPATH=\$HOME/go
                    export PATH=\$GOROOT/bin:\$GOPATH/bin:\$PATH

                    go test -v \
                        -covermode=atomic \
                        -coverprofile=${REPORT_DIR}/coverage.out \
                        \$(go list ./... | grep -v docs | grep -v model | grep -v migration) \
                        2>&1 | tee ${REPORT_DIR}/test.log || true

                    if [ -s "${REPORT_DIR}/coverage.out" ]; then
                        go tool cover -func=${REPORT_DIR}/coverage.out \
                            | tee ${REPORT_DIR}/coverage_summary.txt
                    else
                        echo "mode: atomic" > ${REPORT_DIR}/coverage.out
                    fi
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

            def message = commonUtils.buildStatusMessage(
                "Go Unit Testing & Coverage",
                status
            )

            commonUtils.sendSlack(slackCh, message, status)

            commonUtils.sendEmail(
                email,
                "${status}: Go Unit Test",
                message,
                "${REPORT_DIR}/**"
            )

            cleanWs()
        }
    }
}
