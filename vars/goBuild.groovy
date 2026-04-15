def call(Map config) {

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
                export GOROOT=${GO_DIR}
                export GOPATH=\$HOME/go
                export PATH=\$GOROOT/bin:\$GOPATH/bin:\$PATH

                go mod download 2>&1 | tee ${BINARY_DIR}-deps.log || true
                go mod verify
            """
        }

        stage('Code Compilation') {
            sh """
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
                export GOROOT=${GO_DIR}
                export GOPATH=\$HOME/go
                export PATH=\$GOROOT/bin:\$GOPATH/bin:\$PATH

                MANIFEST="${BINARY_DIR}/build-manifest.txt"

                echo "Build Date   : \$(date -u '+%Y-%m-%d %H:%M:%S UTC')" >> \$MANIFEST
                echo "Branch       : ${branch}" >> \$MANIFEST
                echo "Go Version   : \$(go version)" >> \$MANIFEST
                echo "Module       : \$(go list -m)" >> \$MANIFEST
                go list -v ./... >> \$MANIFEST
                ls -lh ${BINARY} >> \$MANIFEST
                file ${BINARY} >> \$MANIFEST
                go list -m all >> \$MANIFEST
            """
        }

        stage('Archive Artifacts') {
            archiveArtifacts artifacts: "${BINARY_DIR}/**", fingerprint: true
        }

        currentBuild.result = 'SUCCESS'

    } catch (err) {
        currentBuild.result = 'FAILURE'
        throw err

    } finally {

        def status         = currentBuild.currentResult
        def buildLog       = "${env.BUILD_URL}artifact/${BINARY_DIR}/build-output.log"
        def manifestReport = "${env.BUILD_URL}artifact/${BINARY_DIR}/build-manifest.txt"

        // Slack Notification
        slackSend(
            channel: slackChannel,
            color: (status == 'SUCCESS') ? 'good' : 'danger',
            message: "*${status}* - Employee API Build\n" +
                     "*Job Name:* ${env.JOB_NAME}\n" +
                     "*Build Number:* #${env.BUILD_NUMBER}\n" +
                     "*Branch:* ${branch}\n" +
                     "*Status:* ${status}\n" +
                     "<${env.BUILD_URL}|View Build> | <${buildLog}|Build Log>"
        )

        // Email Notification (FIXED)
        if (email?.trim()) {
            emailext(
                to: email,
                subject: "${status}: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                body: """
                Build Status: ${status}

                Job: ${env.JOB_NAME}
                Build: #${env.BUILD_NUMBER}
                Branch: ${branch}

                View Build: ${env.BUILD_URL}
                Build Log: ${buildLog}
                Manifest: ${manifestReport}
                """,
                attachmentsPattern: "${BINARY_DIR}/**",
                recipientProviders: [
                    [$class: 'DevelopersRecipientProvider'],
                    [$class: 'RequesterRecipientProvider']
                ],
                replyTo: email
            )
        }

        cleanWs()
    }
}
