def call(Map config) {
    node {
        def repoUrl          = config.repoUrl
        def branch           = config.branch           ?: 'main'
        def gitCredentialsId = config.gitCredentialsId ?: ''
        def sonarProjectKey  = config.sonarProjectKey  ?: 'employee-api'
        def sonarProjectName = config.sonarProjectName ?: 'Employee API'
        def slackChannel     = config.slackChannel     ?: '#ci-operation-notifications'
        def GO_VERSION       = config.goVersion        ?: '1.22.5'
        def SONAR_VERSION    = '5.0.1.3006'
        def REPORT_DIR       = 'reports'

        try {

            // ─────────────────────────────────────────────────────────────
            stage('Clean Workspace') {
                cleanWs()
            }

            // ─────────────────────────────────────────────────────────────
            stage('Checkout Code') {
                if (gitCredentialsId) {
                    git branch: branch,
                        url: repoUrl,
                        credentialsId: gitCredentialsId
                } else {
                    git branch: branch,
                        url: repoUrl
                }
            }

            // ─────────────────────────────────────────────────────────────
            stage('Setup Go') {
                sh """
                    GO_VERSION=${GO_VERSION}
                    if [ ! -d "go" ]; then
                        echo "==> Downloading Go \${GO_VERSION}"
                        curl -sLO https://go.dev/dl/go\${GO_VERSION}.linux-amd64.tar.gz
                        tar -xzf go\${GO_VERSION}.linux-amd64.tar.gz
                        rm  -f   go\${GO_VERSION}.linux-amd64.tar.gz
                    else
                        echo "==> Go already present, skipping download"
                    fi
                    export GOROOT=\$(pwd)/go
                    export PATH=\$GOROOT/bin:\$PATH
                    go version
                """
            }

            // ─────────────────────────────────────────────────────────────
            stage('Setup SonarScanner') {
                sh """
                    SONAR_VERSION=${SONAR_VERSION}
                    if [ ! -f sonar-scanner/bin/sonar-scanner ]; then
                        echo "==> Downloading sonar-scanner \${SONAR_VERSION}"
                        curl -sLO https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-\${SONAR_VERSION}-linux.zip
                        unzip -q sonar-scanner-cli-\${SONAR_VERSION}-linux.zip
                        mv sonar-scanner-\${SONAR_VERSION}-linux sonar-scanner
                        rm -f sonar-scanner-cli-\${SONAR_VERSION}-linux.zip
                        echo "==> sonar-scanner installed"
                    else
                        echo "==> sonar-scanner already present, skipping download"
                    fi
                    sonar-scanner/bin/sonar-scanner --version
                """
            }

            // ─────────────────────────────────────────────────────────────
            stage('Prepare Reports') {
                sh "mkdir -p ${REPORT_DIR}"
            }

            // ─────────────────────────────────────────────────────────────
            stage('Build') {
                sh """
                    export GOROOT=\$(pwd)/go
                    export PATH=\$GOROOT/bin:\$PATH
                    echo "========== BUILD REPORT =========="
                    go list ./... 2>/dev/null \\
                        | grep -v "/go/test" \\
                        | xargs -r go build 2>&1 \\
                        | tee ${REPORT_DIR}/build.log
                    echo "=================================="
                """
            }

            // ─────────────────────────────────────────────────────────────
            stage('Test') {
                sh """
                    export GOROOT=\$(pwd)/go
                    export PATH=\$GOROOT/bin:\$PATH
                    echo "========== TEST REPORT =========="
                    go list ./... 2>/dev/null \\
                        | grep -v "/go/test" \\
                        | xargs -r go test -v \\
                            -coverprofile=${REPORT_DIR}/coverage.out \\
                            -covermode=atomic 2>&1 \\
                        | tee ${REPORT_DIR}/test.log

                    echo "==> Generating HTML coverage report"
                    export GOROOT=\$(pwd)/go
                    export PATH=\$GOROOT/bin:\$PATH
                    go tool cover \\
                        -html=${REPORT_DIR}/coverage.out \\
                        -o ${REPORT_DIR}/coverage.html 2>/dev/null || true

                    echo "================================="
                """
            }

            // ─────────────────────────────────────────────────────────────
            stage('SonarQube Analysis') {
                withSonarQubeEnv('SonarQube') {
                    sh """
                        export GOROOT=\$(pwd)/go
                        export PATH=\$GOROOT/bin:\$PATH
                        export PATH=\$(pwd)/sonar-scanner/bin:\$PATH

                        echo "========== SONARQUBE ANALYSIS =========="
                        sonar-scanner \\
                            -Dsonar.projectKey=${sonarProjectKey} \\
                            -Dsonar.projectName="${sonarProjectName}" \\
                            -Dsonar.sources=. \\
                            -Dsonar.exclusions=**/vendor/**,**/go/test/**,**/*.html \\
                            -Dsonar.tests=. \\
                            -Dsonar.test.inclusions=**/*_test.go \\
                            -Dsonar.go.coverage.reportPaths=${REPORT_DIR}/coverage.out \\
                            -Dsonar.projectBaseDir=. \\
                            | tee ${REPORT_DIR}/sonar.log
                        echo "========================================"
                    """
                }
            }

            // ─────────────────────────────────────────────────────────────
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
                def status  = currentBuild.result ?: 'FAILURE'
                def color   = (status == 'SUCCESS') ? 'good' : 'danger'
                def emoji   = (status == 'SUCCESS') ? '✅' : '❌'

                slackSend(
                    channel: slackChannel,
                    color  : color,
                    message: """\
${emoji} *${status}* - Go CI | Employee API
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
