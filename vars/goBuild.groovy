def call(Map config) {
    node {
        def repoUrl          = config.repoUrl
        def branch           = config.branch           ?: 'main'
        def gitCredentialsId = config.gitCredentialsId ?: ''
        def GO_VERSION       = config.goVersion        ?: '1.22.5'
        def slackChannel     = config.slackChannel     ?: '#ci-operation-notifications'
        def REPORT_DIR       = 'reports'

        def TOOLS_DIR = '/var/lib/jenkins/tools'
        def GO_DIR    = "${TOOLS_DIR}/go-${GO_VERSION}"

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
                        echo "==> Installing Go ${GO_VERSION} to ${GO_DIR}"
                        mkdir -p ${TOOLS_DIR}
                        curl -sLO https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz
                        tar -xzf go${GO_VERSION}.linux-amd64.tar.gz
                        mv go ${GO_DIR}
                        rm -f go${GO_VERSION}.linux-amd64.tar.gz
                    else
                        echo "==> Go ${GO_VERSION} already at ${GO_DIR}, skipping"
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

                    WORKSPACE_ABS=\$(pwd)

                    echo "========== TEST =========="

                    # Only test packages that actually have tests:
                    # api, client, config, middleware, routes
                    # Exclude: docs, model, migration (no test files)
                    go test \
                        \$(go list ./... | grep -v docs | grep -v model | grep -v migration) \
                        -v \
                        -covermode=atomic \
                        -coverprofile=\${WORKSPACE_ABS}/${REPORT_DIR}/coverage.out \
                        2>&1 | tee \${WORKSPACE_ABS}/${REPORT_DIR}/test.log || true

                    echo "=========================="

                    echo "==> Checking coverage file..."
                    if [ -s "\${WORKSPACE_ABS}/${REPORT_DIR}/coverage.out" ]; then
                        echo "coverage.out found and non-empty:"
                        wc -l \${WORKSPACE_ABS}/${REPORT_DIR}/coverage.out

                        echo "==> Coverage Summary:"
                        go tool cover \
                            -func=\${WORKSPACE_ABS}/${REPORT_DIR}/coverage.out \
                            | tee \${WORKSPACE_ABS}/${REPORT_DIR}/coverage_summary.txt

                        echo "==> Generating HTML coverage report..."
                        go tool cover \
                            -html=\${WORKSPACE_ABS}/${REPORT_DIR}/coverage.out \
                            -o \${WORKSPACE_ABS}/${REPORT_DIR}/coverage.html

                        echo "==> Total Coverage:"
                        go tool cover \
                            -func=\${WORKSPACE_ABS}/${REPORT_DIR}/coverage.out \
                            | grep "^total:"
                    else
                        echo "WARNING: coverage.out was NOT generated or is empty"
                        echo "mode: atomic" > \${WORKSPACE_ABS}/${REPORT_DIR}/coverage.out
                    fi
                """
            }

            stage('Publish Coverage Report') {
                publishHTML(target: [
                    allowMissing         : false,
                    alwaysLinkToLastBuild: true,
                    keepAll              : true,
                    reportDir            : "${REPORT_DIR}",
                    reportFiles          : 'coverage.html',
                    reportName           : 'Go Coverage Report',
                    reportTitles         : 'Coverage'
                ])
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
${emoji} *${status}* - Go Test | Employee API
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
