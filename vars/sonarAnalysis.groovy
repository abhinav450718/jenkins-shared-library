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
                    curl -sf http://192.168.8.17:9000/api/system/status | grep -q UP \
                        && echo "SonarQube is UP" \
                """
            }

            stage('Generate Coverage For Sonar') {
                sh """
                    set -e
                    export GOROOT=${GO_DIR}
                    export GOPATH=\$HOME/go
                    export PATH=\$GOROOT/bin:\$GOPATH/bin:\$PATH

                    go version

                    pwd

                    go list ./...

                    go test -v \
                        -covermode=atomic \
                        -coverprofile="${REPORT_DIR}/coverage.out" \
                        ./api/... \
                        ./client/... \
                        ./config/... \
                        ./middleware/... \
                        ./routes/... \
                        2>&1 | tee "${REPORT_DIR}/test.log" || true

                    wc -l "${REPORT_DIR}/coverage.out"

                    head -10 "${REPORT_DIR}/coverage.out"

                    go tool cover -func="${REPORT_DIR}/coverage.out" || true
                """
            }

            stage('SonarQube Analysis') {
                withSonarQubeEnv('SonarQube') {
                    sh """
                        set -e
                        export PATH=${SONAR_DIR}/bin:\$PATH

                        cat "${REPORT_DIR}/coverage.out"

                        sonar-scanner \
                            -Dsonar.projectKey=${sonarProjectKey} \
                            -Dsonar.projectName="${sonarProjectName}" \
                            -Dsonar.projectVersion=1.0 \
                            -Dsonar.sources=. \
                            -Dsonar.language=go \
                            -Dsonar.go.coverage.reportPaths="${REPORT_DIR}/coverage.out" \
                            -Dsonar.exclusions=**/vendor/**,**/docs/**,**/reports/**,**/*.md,**/*_test.go \
                            -Dsonar.test.inclusions=**/*_test.go \
                            -Dsonar.sourceEncoding=UTF-8 \
                            2>&1 | tee "${REPORT_DIR}/sonar.log"

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
