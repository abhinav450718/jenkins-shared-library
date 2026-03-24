def call(Map config) {

    node {

        def repoUrl          = config.repoUrl
        def branch           = config.branch ?: 'main'
        def sonarProjectKey  = config.sonarProjectKey ?: 'employee-api'
        def sonarProjectName = config.sonarProjectName ?: 'employee-api'
        def slackChannel     = config.slackChannel ?: '#ci-operation-notifications'

        def REPORT_DIR = "reports"

        try {

            stage('Clean Workspace') {
                deleteDir()
            }

            stage('Checkout Code') {
                git branch: branch, url: repoUrl
            }

            // -------------------------
            // Setup Go
            // -------------------------
            stage('Setup Go') {
                sh '''
                GO_VERSION=1.22.5

                curl -sLO https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz
                tar -xzf go${GO_VERSION}.linux-amd64.tar.gz

                export GOROOT=$(pwd)/go
                export PATH=$GOROOT/bin:$PATH

                go version
                '''
            }

            // -------------------------
            // Setup SonarScanner (LOCAL FIX)
            // -------------------------
            stage('Setup SonarScanner') {
                sh '''
                echo "Installing SonarScanner locally..."

                curl -sLo sonar.zip https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-5.0.1.3006-linux.zip
                unzip -q sonar.zip

                export PATH=$(pwd)/sonar-scanner-*/bin:$PATH

                sonar-scanner --version
                '''
            }

            // -------------------------
            // Create Report Directory
            // -------------------------
            stage('Prepare Reports') {
                sh "mkdir -p ${REPORT_DIR}"
            }

            // -------------------------
            // Code Compilation
            // -------------------------
            stage('Build') {
                sh """
                export GOROOT=\$(pwd)/go
                export PATH=\$GOROOT/bin:\$PATH

                go list ./... 2>/dev/null | grep -v "/go/test" | xargs -r go build 2>&1 | tee ${REPORT_DIR}/build.log
                """
            }

            // -------------------------
            // Unit Testing
            // -------------------------
            stage('Test') {
                sh """
                export GOROOT=\$(pwd)/go
                export PATH=\$GOROOT/bin:\$PATH

                go list ./... 2>/dev/null | grep -v "/go/test" | xargs -r go test -v 2>&1 | tee ${REPORT_DIR}/test.log
                """
            }

            // -------------------------
            // SonarQube Analysis
            // -------------------------
            stage('SonarQube Analysis') {
                withSonarQubeEnv('SonarQube') {
                    sh """
                    export GOROOT=\$(pwd)/go
                    export PATH=\$GOROOT/bin:\$(pwd)/sonar-scanner-*/bin:\$PATH

                    sonar-scanner \
                    -Dsonar.projectKey=${sonarProjectKey} \
                    -Dsonar.projectName=${sonarProjectName} \
                    -Dsonar.sources=. \
                    -Dsonar.language=go \
                    -Dsonar.projectBaseDir=. \
                    -Dsonar.log.level=INFO | tee ${REPORT_DIR}/sonar.log
                    """
                }
            }

            // -------------------------
            // Quality Gate
            // -------------------------
            stage('Quality Gate') {
                timeout(time: 3, unit: 'MINUTES') {
                    script {
                        def qg = waitForQualityGate()
                        writeFile file: "${REPORT_DIR}/quality-gate.txt", text: "Status: ${qg.status}"

                        if (qg.status != 'OK') {
                            currentBuild.result = 'UNSTABLE'
                        }
                    }
                }
            }

            // -------------------------
            // Archive ALL Reports
            // -------------------------
            stage('Archive Reports') {
                archiveArtifacts artifacts: "${REPORT_DIR}/**", fingerprint: true
            }

            currentBuild.result = 'SUCCESS'

        } catch (err) {

            currentBuild.result = 'FAILURE'
            echo "Error: ${err}"
            throw err

        } finally {

            stage('Post Actions') {

                if (currentBuild.result == 'SUCCESS') {

                    slackSend(
                        channel: slackChannel,
                        color: 'good',
                        message: "SUCCESS - Go CI\nJob: ${env.JOB_NAME}\nBuild: #${env.BUILD_NUMBER}"
                    )

                } else if (currentBuild.result == 'UNSTABLE') {

                    slackSend(
                        channel: slackChannel,
                        color: 'warning',
                        message: "UNSTABLE - Quality Gate Failed\nJob: ${env.JOB_NAME}\nBuild: #${env.BUILD_NUMBER}"
                    )

                } else {

                    slackSend(
                        channel: slackChannel,
                        color: 'danger',
                        message: "FAILED - Go CI\nJob: ${env.JOB_NAME}\nBuild: #${env.BUILD_NUMBER}"
                    )
                }

                cleanWs()
            }
        }
    }
}
