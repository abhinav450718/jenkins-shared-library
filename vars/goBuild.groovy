/**
 * vars/goBuild.groovy
 * Jenkins Shared Library — Employee API (Go)
 *
 * Usage in Jenkinsfile:
 *   @Library('employee-shared-lib') _
 *   goBuild(
 *       repoUrl         : 'https://github.com/org/employee-api.git',
 *       branch          : 'main',
 *       sonarProjectKey : 'employee-api',
 *       sonarProjectName: 'employee-api',
 *       slackChannel    : '#ci-operation-notifications'
 *   )
 */

def call(Map config) {
    node {
        def repoUrl          = config.repoUrl
        def branch           = config.branch           ?: 'main'
        def sonarProjectKey  = config.sonarProjectKey  ?: 'employee-api'
        def sonarProjectName = config.sonarProjectName ?: 'employee-api'
        def slackChannel     = config.slackChannel     ?: '#ci-operation-notifications'
        def GO_VERSION       = config.goVersion        ?: '1.22.5'
        def REPORT_DIR       = 'reports'

        try {

            // ─────────────────────────────────────────────────────────────
            stage('Clean Workspace') {
                cleanWs()
            }

            // ─────────────────────────────────────────────────────────────
            stage('Checkout Code') {
                git branch: branch, url: repoUrl
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
            stage('Prepare Reports') {
                sh "mkdir -p ${REPORT_DIR}"
            }

            // ─────────────────────────────────────────────────────────────
            stage('Build') {
                sh """
                    export GOROOT=\$(pwd)/go
                    export PATH=\$GOROOT/bin:\$PATH
                    echo "========== BUILD REPORT =========="
                    go list ./... 2>/dev/null \
                        | grep -v "/go/test" \
                        | xargs -r go build 2>&1 \
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
                    go list ./... 2>/dev/null \
                        | grep -v "/go/test" \
                        | xargs -r go test -v 2>&1 \
                        | tee ${REPORT_DIR}/test.log
                    echo "================================="
                """
            }

            // ─────────────────────────────────────────────────────────────
            stage('SonarQube Analysis') {
                withSonarQubeEnv('SonarQube') {
                    sh """
                        export GOROOT=\$(pwd)/go
                        export PATH=\$GOROOT/bin:\$PATH
                        echo "========== SONARQUBE ANALYSIS =========="
                        npx sonar-scanner \\
                            -Dsonar.projectKey=${sonarProjectKey} \\
                            -Dsonar.projectName=${sonarProjectName} \\
                            -Dsonar.sources=. \\
                            -Dsonar.exclusions=**/go/test/** \\
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
                if (currentBuild.result == 'SUCCESS') {
                    slackSend(
                        channel: slackChannel,
                        color  : 'good',
                        message: """\
SUCCESS ✅ - Go CI | Employee API
Job    : ${env.JOB_NAME}
Branch : ${branch}
Build  : #${env.BUILD_NUMBER}
URL    : ${env.BUILD_URL}""".stripIndent()
                    )
                } else {
                    slackSend(
                        channel: slackChannel,
                        color  : 'danger',
                        message: """\
FAILED ❌ - Go CI | Employee API
Job    : ${env.JOB_NAME}
Branch : ${branch}
Build  : #${env.BUILD_NUMBER}
URL    : ${env.BUILD_URL}""".stripIndent()
                    )
                }
                cleanWs()
            }
        }
    }
}
