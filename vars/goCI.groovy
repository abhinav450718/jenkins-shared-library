def call(Map config) {

    node {

        def repoUrl           = config.repoUrl
        def branch            = config.branch ?: 'main'
        def sonarProjectKey   = config.sonarProjectKey
        def sonarProjectName  = config.sonarProjectName
        def slackChannel      = config.slackChannel ?: '#ci-operation-notifications'

        try {

            stage('Clean Workspace') {
                deleteDir()
            }

            stage('Checkout Code') {
                git branch: branch, url: repoUrl
            }

            stage('Verify Go Environment') {
                sh '''
                echo "Go Version:"
                go version
                '''
            }

            // -------------------------
            // Code Compilation
            // -------------------------
            stage('Code Compilation') {
                sh '''
                echo "Compiling Go code..."
                go mod tidy
                go build ./...
                '''
            }

            // -------------------------
            // Unit Testing
            // -------------------------
            stage('Unit Testing') {
                sh '''
                echo "Running unit tests..."
                go test ./... -v
                '''
            }

            // -------------------------
            // Static Code Analysis
            // -------------------------
            stage('SonarQube Analysis') {
                withSonarQubeEnv('SonarQube') {
                    sh """
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
                        }
                    }
                }
            }

            currentBuild.result = 'SUCCESS'

        } catch (err) {

            currentBuild.result = 'FAILURE'
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
