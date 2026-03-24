def call(Map config) {

    node {

        def repoUrl          = config.repoUrl
        def branch           = config.branch ?: 'main'
        def sonarProjectKey  = config.sonarProjectKey ?: 'employee-api'
        def sonarProjectName = config.sonarProjectName ?: 'employee-api'
        def slackChannel     = config.slackChannel ?: '#ci-operation-notifications'

        try {

            stage('Clean Workspace') {
                deleteDir()
            }

            stage('Checkout Code') {
                git branch: branch, url: repoUrl
            }

            // -------------------------
            // Setup Go Locally
            // -------------------------
            stage('Setup Go (Local)') {
                sh '''
                echo "Installing Go locally..."

                GO_VERSION=1.22.5

                curl -LO https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz
                tar -xzf go${GO_VERSION}.linux-amd64.tar.gz

                export GOROOT=$(pwd)/go
                export PATH=$GOROOT/bin:$PATH

                echo "Go Version:"
                go version
                '''
            }

            // -------------------------
            // Code Compilation (FIXED)
            // -------------------------
            stage('Code Compilation') {
                sh '''
                export GOROOT=$(pwd)/go
                export PATH=$GOROOT/bin:$PATH

                echo "Building only valid Go packages..."

                # ONLY build valid modules (exclude go/test completely)
                go list ./... 2>/dev/null | grep -v "/go/test" | xargs -r go build
                '''
            }

            // -------------------------
            // Unit Testing (FIXED)
            // -------------------------
            stage('Unit Testing') {
                sh '''
                export GOROOT=$(pwd)/go
                export PATH=$GOROOT/bin:$PATH

                echo "Running tests on valid packages..."

                go list ./... 2>/dev/null | grep -v "/go/test" | xargs -r go test -v
                '''
            }

            // -------------------------
            // SonarQube Analysis
            // -------------------------
            stage('SonarQube Analysis') {
                withSonarQubeEnv('SonarQube') {
                    sh """
                    export GOROOT=\$(pwd)/go
                    export PATH=\$GOROOT/bin:\$PATH

                    sonar-scanner \
                    -Dsonar.projectKey=${sonarProjectKey} \
                    -Dsonar.projectName=${sonarProjectName} \
                    -Dsonar.sources=. \
                    -Dsonar.language=go
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
                        if (qg.status != 'OK') {
                            currentBuild.result = 'UNSTABLE'
                            echo "Quality Gate Failed: ${qg.status}"
                        } else {
                            echo "Quality Gate Passed"
                        }
                    }
                }
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
                        message: "SUCCESS - Go CI\nJob: ${env.JOB_NAME}\nBuild: #${env.BUILD_NUMBER}\nURL: ${env.BUILD_URL}"
                    )

                } else if (currentBuild.result == 'UNSTABLE') {

                    slackSend(
                        channel: slackChannel,
                        color: 'warning',
                        message: "UNSTABLE - Quality Gate Failed\nJob: ${env.JOB_NAME}\nBuild: #${env.BUILD_NUMBER}\nURL: ${env.BUILD_URL}"
                    )

                } else {

                    slackSend(
                        channel: slackChannel,
                        color: 'danger',
                        message: "FAILED - Go CI\nJob: ${env.JOB_NAME}\nBuild: #${env.BUILD_NUMBER}\nURL: ${env.BUILD_URL}"
                    )
                }

                cleanWs()
            }
        }
    }
}
