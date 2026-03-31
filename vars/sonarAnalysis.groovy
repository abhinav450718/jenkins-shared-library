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

        def TOOLS_DIR = '/var/lib/jenkins/tools'
        def GO_DIR    = "${TOOLS_DIR}/go-${GO_VERSION}"
        def SONAR_DIR = "${TOOLS_DIR}/sonar-scanner-${SONAR_VERSION}"

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

            stage('Setup SonarScanner') {
                sh """
                    set -e
                    if [ ! -f "${SONAR_DIR}/bin/sonar-scanner" ]; then
                        echo "==> Installing sonar-scanner ${SONAR_VERSION}"
                        mkdir -p ${TOOLS_DIR}
                        curl -sLO https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-${SONAR_VERSION}-linux.zip
                        unzip -q sonar-scanner-cli-${SONAR_VERSION}-linux.zip -d ${TOOLS_DIR}
                        mv ${TOOLS_DIR}/sonar-scanner-${SONAR_VERSION}-linux ${SONAR_DIR}
                        rm -f sonar-scanner-cli-${SONAR_VERSION}-linux.zip
                    else
                        echo "==> sonar-scanner already at ${SONAR_DIR}, skipping"
                    fi
                    ${SONAR_DIR}/bin/sonar-scanner --version
                """
            }

            stage('Prepare Reports') {
                sh "mkdir -p ${REPORT_DIR}"
            }

            stage('Verify SonarQube Reachable') {
                sh """
                    echo "==> Checking SonarQube connectivity..."
                    curl -sf http://192.168.8.17:9000/api/system/status | grep -q UP \
                        && echo "SonarQube is UP" \
                        || (echo "ERROR: SonarQube is UNREACHABLE" && exit 1)
                """
            }

            stage('Generate Coverage For Sonar') {
                sh """
                    set -e
                    export GOROOT=${GO_DIR}
                    export GOPATH=\$HOME/go
                    export PATH=\$GOROOT/bin:\$GOPATH/bin:\$PATH

                    go test -v \
                        -covermode=atomic \
                        -coverprofile="${REPORT_DIR}/coverage.out" \
                        \$(go list ./... | grep -v docs | grep -v model | grep -v migration) \
                        2>&1 | tee "${REPORT_DIR}/test.log" || true

                    if [ ! -f "${REPORT_DIR}/coverage.out" ]; then
                        echo "WARNING: coverage.out not generated, creating empty file"
                        echo "mode: atomic" > "${REPORT_DIR}/coverage.out"
                    fi
                """
            }

            stage('SonarQube Analysis') {
                withSonarQubeEnv('SonarQube') {
                    sh """
                        set -e
                        export PATH=${SONAR_DIR}/bin:\$PATH

                        echo "==> Running SonarQube Analysis..."
                        echo "==> SONAR_HOST_URL : \${SONAR_HOST_URL}"

                        sonar-scanner \
                            -Dsonar.projectKey=${sonarProjectKey} \
                            -Dsonar.projectName="${sonarProjectName}" \
                            -Dsonar.projectVersion=1.0 \
                            -Dsonar.sources=. \
                            -Dsonar.exclusions=**/vendor/**,**/*_test.go,**/docs/**,**/reports/**,**/*.md \
                            -Dsonar.tests=. \
                            -Dsonar.test.inclusions=**/*_test.go \
                            -Dsonar.go.coverage.reportPaths="${REPORT_DIR}/coverage.out" \
                            -Dsonar.sourceEncoding=UTF-8 \
                            2>&1 | tee "${REPORT_DIR}/sonar.log"

                        echo "==> SonarQube Analysis complete"
                    """
                }
            }

            stage('Quality Gate') {
                timeout(time: 5, unit: 'MINUTES') {
                    def qg = waitForQualityGate()
                    if (qg.status != 'OK') {
                        error "Pipeline aborted: Quality Gate status = ${qg.status}"
                    }
                }
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
                slackSend(
                    channel: slackChannel,
                    color  : color,
                    message: """\
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
