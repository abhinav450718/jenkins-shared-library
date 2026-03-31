def call(Map config) {
    node {
        def repoUrl          = config.repoUrl
        def branch           = config.branch           ?: 'main'
        def gitCredentialsId = config.gitCredentialsId ?: ''
        def GO_VERSION       = config.goVersion        ?: '1.22.5'
        def slackChannel     = config.slackChannel     ?: '#ci-operation-notifications'

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
                        echo "==> Installing Go ${GO_VERSION} to ${GO_DIR}"
                        mkdir -p ${TOOLS_DIR}
                        curl -sLO https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz
                        tar -xzf go${GO_VERSION}.linux-amd64.tar.gz
                        mv go ${GO_DIR}
                        rm -f go${GO_VERSION}.linux-amd64.tar.gz
                    else
                        echo "==> Go ${GO_VERSION} already cached at ${GO_DIR}, skipping install"
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

                    echo "==> Downloading Go module dependencies..."
                    go mod download
                    echo "==> go mod verify..."
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

                    echo "========== COMPILATION =========="
                    echo "Packages being compiled:"
                    go list ./...
                    echo "================================="

                    # Build the final binary from main.go (root package)
                    # -v  : verbose — prints each package name as it is compiled
                    # -o  : output binary path
                    go build -v -o ${BINARY} .

                    echo "==> Build complete. Binary info:"
                    ls -lh ${BINARY}
                    file  ${BINARY}
                """
            }

            stage('Generate Build Manifest') {
                sh """
                    set -e
                    export GOROOT=${GO_DIR}
                    export GOPATH=\$HOME/go
                    export PATH=\$GOROOT/bin:\$GOPATH/bin:\$PATH

                    MANIFEST="${BINARY_DIR}/build-manifest.txt"

                    echo "===== Employee API — Build Manifest =====" >  \$MANIFEST
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
                    file  ${BINARY} >> \$MANIFEST
                    echo ""                                                    >> \$MANIFEST
                    echo "--- Module Dependencies ---"                         >> \$MANIFEST
                    go list -m all  >> \$MANIFEST

                    echo "==> Manifest written to \$MANIFEST"
                    cat \$MANIFEST
                """
            }

            stage('Archive Artifacts') {
                archiveArtifacts artifacts: "${BINARY_DIR}/**", fingerprint: true
            }

            currentBuild.result = 'SUCCESS'

        } catch (err) {
            currentBuild.result = 'FAILURE'
            echo "Pipeline error: ${err}"
            throw err

        } finally {
            stage('Notify') {
                def status = currentBuild.result ?: 'FAILURE'
                def color  = (status == 'SUCCESS') ? 'good'   : 'danger'
                def emoji  = (status == 'SUCCESS') ? '✅'     : '❌'
                slackSend(
                    channel: slackChannel,
                    color  : color,
                    message: """\
${emoji} *${status}* — Go Build | Employee API
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
