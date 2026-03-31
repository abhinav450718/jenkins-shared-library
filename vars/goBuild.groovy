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

        // ── Tools live OUTSIDE the workspace so sonar never scans them ──
        def TOOLS_DIR  = '/var/lib/jenkins/tools'
        def GO_DIR     = "${TOOLS_DIR}/go-${GO_VERSION}"
        def SONAR_DIR  = "${TOOLS_DIR}/sonar-scanner-${SONAR_VERSION}"

        try {

            // ─────────────────────────────────────────────────────────────
            stage('Clean Workspace') {
                cleanWs()
            }

            // ─────────────────────────────────────────────────────────────
            stage('Checkout Code') {
                if (gitCredentialsId) {
                    git branch: branch, url: repoUrl, credentialsId: gitCredentialsId
                } else {
                    git branch: branch, url: repoUrl
                }
            }

            // ─────────────────────────────────────────────────────────────
            stage('Setup Go') {
                sh """
                    set -e
                    if [ ! -f "${GO_DIR}/bin/go" ]; then
                        echo "==> Installing Go ${GO_VERSION} to ${GO_DIR}"
                        mkdir -p ${TOOLS_DIR}
                        curl -sLO https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz
                        tar -xzf go${GO_VERSION}.linux-amd64.tar.gz
                        # ── extract to tools dir, NOT the workspace ──
                        mv go ${GO_DIR}
                        rm -f go${GO_VERSION}.linux-amd64.tar.gz
                    else
                        echo "==> Go ${GO_VERSION} already at ${GO_DIR}, skipping"
                    fi
                    ${GO_DIR}/bin/go version
                """
            }

            // ─────────────────────────────────────────────────────────────
            stage('Setup SonarScanner') {
                sh """
                    set -e
                    if [ ! -f "${SONAR_DIR}/bin/sonar-scanner" ]; then
                        echo "==> Installing sonar-scanner ${SONAR_VERSION} to ${SONAR_DIR}"
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

            // ─────────────────────────────────────────────────────────────
            stage('Prepare Reports') {
                sh "mkdir -p ${REPORT_DIR}"
            }

            // ─────────────────────────────────────────────────────────────
            stage('Verify SonarQube Reachable') {
                sh """
                    echo "==> Checking SonarQube connectivity..."
                    curl -sf ${env.SONAR_HOST_URL ?: 'http://192.168.8.17:9000'}/api/system/status \
                        | grep -q 'UP' \
                        && echo "SonarQube is UP and reachable" \
                        || (echo "ERROR: SonarQube is UNREACHABLE" && exit 1)
                """
            }

            // ─────────────────────────────────────────────────────────────
            stage('Build') {
                sh """
                    set -e
                    export GOROOT=${GO_DIR}
                    export GOPATH=\$HOME/go
                    export PATH=\$GOROOT/bin:\$GOPATH/bin:\$PATH

                    echo "==> Go binary: \$(which go)"
                    echo "==> Go version: \$(go version)"
                    echo "==> Working dir: \$(pwd)"

                    echo "========== BUILD =========="
                    go build ./... 2>&1 | tee ${REPORT_DIR}/build.log
                    echo "==> Build succeeded"
                    echo "==========================="
                """
            }

            // ─────────────────────────────────────────────────────────────
            stage('Test') {
                sh """
                    set -e
                    export GOROOT=${GO_DIR}
                    export GOPATH=\$HOME/go
                    export PATH=\$GOROOT/bin:\$GOPATH/bin:\$PATH

                    WORKSPACE_ABS=\$(pwd)

                    echo "========== TEST =========="
                    # -coverpkg=./... ensures ALL packages are measured,
                    # even those not directly tested
                    go test -v \
                        -covermode=atomic \
                        -coverpkg=./... \
                        -coverprofile=\${WORKSPACE_ABS}/${REPORT_DIR}/coverage.out \
                        ./... 2>&1 | tee \${WORKSPACE_ABS}/${REPORT_DIR}/test.log || true

                    echo "==> Checking coverage file..."
                    if [ -f "\${WORKSPACE_ABS}/${REPORT_DIR}/coverage.out" ]; then
                        echo "coverage.out found:"
                        wc -l \${WORKSPACE_ABS}/${REPORT_DIR}/coverage.out
                        go tool cover \
                            -func=\${WORKSPACE_ABS}/${REPORT_DIR}/coverage.out \
                            | tail -3 | tee \${WORKSPACE_ABS}/${REPORT_DIR}/coverage_summary.txt
                        go tool cover \
                            -html=\${WORKSPACE_ABS}/${REPORT_DIR}/coverage.out \
                            -o \${WORKSPACE_ABS}/${REPORT_DIR}/coverage.html
                    else
                        echo "WARNING: coverage.out was NOT generated"
                        # Create empty file so sonar-scanner does not fail
                        echo "mode: atomic" > \${WORKSPACE_ABS}/${REPORT_DIR}/coverage.out
                    fi
                    echo "=========================="
                """
            }

            // ─────────────────────────────────────────────────────────────
            stage('SonarQube Analysis') {
                withSonarQubeEnv('SonarQube') {
                    sh """
                        set -e
                        export GOROOT=${GO_DIR}
                        export GOPATH=\$HOME/go
                        export PATH=\$GOROOT/bin:\$GOPATH/bin:${SONAR_DIR}/bin:\$PATH

                        WORKSPACE_ABS=\$(pwd)

                        echo "==> Workspace   : \${WORKSPACE_ABS}"
                        echo "==> Coverage    : \$(ls -lh \${WORKSPACE_ABS}/${REPORT_DIR}/coverage.out)"
                        echo "==> SONAR_HOST  : \${SONAR_HOST_URL}"
                        echo "==> SONAR_TOKEN : \${SONAR_TOKEN:0:6}***"

                        # ── List what sonar will actually scan BEFORE running ──
                        echo "==> App Go files found:"
                        find \${WORKSPACE_ABS} \
                            -name "*.go" \
                            -not -path "*/vendor/*" \
                            -not -path "*_test.go" \
                            | head -20
                        echo "==> Total app Go files:"
                        find \${WORKSPACE_ABS} \
                            -name "*.go" \
                            -not -path "*/vendor/*" \
                            -not -path "*_test.go" \
                            | wc -l

                        echo "========== SONARQUBE ANALYSIS =========="
                        sonar-scanner \
                            -Dsonar.projectKey=${sonarProjectKey} \
                            -Dsonar.projectName="${sonarProjectName}" \
                            -Dsonar.projectVersion=1.0 \
                            -Dsonar.sources=\${WORKSPACE_ABS} \
                            -Dsonar.exclusions=**/vendor/**,**/*_test.go,**/testdata/**,**/*.html,**/reports/**,**/*.md \
                            -Dsonar.tests=\${WORKSPACE_ABS} \
                            -Dsonar.test.inclusions=**/*_test.go \
                            -Dsonar.go.coverage.reportPaths=\${WORKSPACE_ABS}/${REPORT_DIR}/coverage.out \
                            -Dsonar.sourceEncoding=UTF-8 \
                            -Dsonar.projectBaseDir=\${WORKSPACE_ABS} \
                            2>&1 | tee \${WORKSPACE_ABS}/${REPORT_DIR}/sonar.log
                        echo "========================================"
                    """
                }
            }

            // ─────────────────────────────────────────────────────────────
            stage('Quality Gate') {
                timeout(time: 5, unit: 'MINUTES') {
                    def qg = waitForQualityGate()
                    if (qg.status != 'OK') {
                        error "Pipeline aborted: Quality Gate status = ${qg.status}"
                    }
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
                def status = currentBuild.result ?: 'FAILURE'
                def color  = (status == 'SUCCESS') ? 'good'  : 'danger'
                def emoji  = (status == 'SUCCESS') ? '✅'    : '❌'
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
