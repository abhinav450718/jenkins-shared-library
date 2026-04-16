def call(Map config) {
    node {
        def repoUrl          = config.repoUrl
        def branch           = config.branch           ?: 'main'
        def gitCredentialsId = config.gitCredentialsId ?: ''
        def GO_VERSION       = config.goVersion        ?: '1.22.5'
        def slackChannel     = config.slackChannel     ?: '#ci-operation-notifications'
        def email            = config.email            ?: ''

        def TOOLS_DIR  = '/var/lib/jenkins/tools'
        def GO_DIR     = "${TOOLS_DIR}/go-${GO_VERSION}"
        def BINARY_DIR = 'build'
        def BINARY     = "${BINARY_DIR}/employee-api"

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

            stage('Download Dependencies') {
                sh """
                    set -e
                    export GOROOT=${GO_DIR}
                    export GOPATH=\$HOME/go
                    export PATH=\$GOROOT/bin:\$GOPATH/bin:\$PATH

                    go mod download 2>&1 | tee build-deps.log || true
                    go mod verify
                """
            }

            stage('Code Compilation') {
                sh """
                    set -e
                    export GOROOT=${GO_DIR}
                    export GOPATH=\$HOME/go
                    export PATH=\$GOROOT/bin:\$GOPATH/bin:\$PATH

                    mkdir -p ${BINARY_DIR}

                    go build -v -o ${BINARY} . 2>&1 | tee ${BINARY_DIR}/build-output.log

                    ls -lh ${BINARY}
                    file ${BINARY}
                """
            }

            stage('Generate Build Manifest') {
                sh """
                    set -e
                    export GOROOT=${GO_DIR}
                    export GOPATH=\$HOME/go
                    export PATH=\$GOROOT/bin:\$GOPATH/bin:\$PATH

                    MANIFEST="${BINARY_DIR}/build-manifest.txt"

                    echo "Build Date   : \$(date -u '+%Y-%m-%d %H:%M:%S UTC')" >> \$MANIFEST
                    echo "Branch       : ${branch}"                            >> \$MANIFEST
                    echo "Go Version   : \$(go version)"                       >> \$MANIFEST
                    echo "Module       : \$(go list -m)"                       >> \$MANIFEST
                    echo ""                                                    >> \$MANIFEST
                    echo "--- Compiled Packages ---"                          >> \$MANIFEST
                    go list -v ./... >> \$MANIFEST
                    echo ""                                                    >> \$MANIFEST
                    echo "--- Binary Details ---"                              >> \$MANIFEST
                    ls -lh ${BINARY} >> \$MANIFEST
                    file ${BINARY}   >> \$MANIFEST
                    echo ""                                                    >> \$MANIFEST
                    echo "--- Module Dependencies ---"                         >> \$MANIFEST
                    go list -m all   >> \$MANIFEST
                """
            }

            stage('Archive Artifacts') {
                archiveArtifacts artifacts: "${BINARY_DIR}/build-output.log, ${BINARY_DIR}/build-manifest.txt, ${BINARY_DIR}/employee-api", fingerprint: true
            }

            currentBuild.result = 'SUCCESS'

        } catch (err) {
            currentBuild.result = 'FAILURE'
            throw err

        } finally {
            stage('Post Actions') {
                def status         = currentBuild.result ?: 'FAILURE'
                def buildLog       = "${env.BUILD_URL}artifact/${BINARY_DIR}/build-output.log"
                def manifestReport = "${env.BUILD_URL}artifact/${BINARY_DIR}/build-manifest.txt"

                if (status == 'FAILURE') {
                    slackSend(
                        channel: slackChannel,
                        color: 'danger',
                        message: "*FAILED* - Employee API Build\n" +
                                 "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                                 "*Job Name:*       " + env.JOB_NAME + "\n" +
                                 "*Build Number:*   #" + env.BUILD_NUMBER + "\n" +
                                 "*Branch:*         " + branch + "\n" +
                                 "*Status:*         Build Failed\n" +
                                 "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                                 "<" + env.BUILD_URL + "|View Build>   |   " +
                                 "<" + buildLog + "|Build Log>"
                    )
                } else {
                    slackSend(
                        channel: slackChannel,
                        color: 'good',
                        message: "*SUCCESS* - Employee API Build\n" +
                                 "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                                 "*Job Name:*       " + env.JOB_NAME + "\n" +
                                 "*Build Number:*   #" + env.BUILD_NUMBER + "\n" +
                                 "*Branch:*         " + branch + "\n" +
                                 "*Status:*         Build Passed\n" +
                                 "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                                 "<" + env.BUILD_URL + "|View Build>   |   " +
                                 "<" + buildLog + "|Build Log>   |   " +
                                 "<" + manifestReport + "|Build Manifest>"
                    )
                }

                if (email?.trim()) {
                    emailext(
                        to: email,
                        subject: "[${status}] ${env.JOB_NAME} - Build #${env.BUILD_NUMBER}",
                        mimeType: 'text/html',
                        body: """
                            <html>
                            <body style="font-family: Arial, sans-serif; font-size: 14px;">
                                <h2 style="color: ${status == 'SUCCESS' ? '#2e7d32' : '#c62828'};">
                                    ${status} - Employee API Build
                                </h2>
                                <hr/>
                                <table cellpadding="6" cellspacing="0">
                                    <tr><td><b>Job Name</b></td><td>${env.JOB_NAME}</td></tr>
                                    <tr><td><b>Build Number</b></td><td>#${env.BUILD_NUMBER}</td></tr>
                                    <tr><td><b>Branch</b></td><td>${branch}</td></tr>
                                    <tr><td><b>Status</b></td><td>${status}</td></tr>
                                </table>
                                <hr/>
                                <p>
                                    <a href="${env.BUILD_URL}">View Build</a> &nbsp;|&nbsp;
                                    <a href="${buildLog}">Build Log</a> &nbsp;|&nbsp;
                                    <a href="${manifestReport}">Build Manifest</a>
                                </p>
                            </body>
                            </html>
                        """,
                        replyTo: email
                    )
                }

                cleanWs()
            }
        }
    }
}
