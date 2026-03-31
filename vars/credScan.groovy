def call(Map config) {
    node {
        def repoUrl          = config.repoUrl
        def branch           = config.branch           ?: 'master'
        def gitCredentialsId = config.gitCredentialsId ?: ''
        def slackChannel     = config.slackChannel     ?: '#ci-operation-notifications'
        def GITLEAKS_VERSION = config.gitleaksVersion  ?: '8.18.2'
        def REPORT_DIR       = 'reports'

        def TOOLS_DIR    = '/var/lib/jenkins/tools'
        def GITLEAKS_DIR = "${TOOLS_DIR}/gitleaks-${GITLEAKS_VERSION}"
        def GITLEAKS_BIN = "${GITLEAKS_DIR}/gitleaks"

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

            stage('Setup Gitleaks') {
                sh """
                    set -e
                    if [ ! -f "${GITLEAKS_BIN}" ]; then
                        echo "==> Installing Gitleaks ${GITLEAKS_VERSION}..."
                        mkdir -p ${GITLEAKS_DIR}
                        curl -sLO https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz
                        tar -xzf gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz -C ${GITLEAKS_DIR}
                        rm -f gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz
                        chmod +x ${GITLEAKS_BIN}
                        echo "==> Gitleaks installed successfully"
                    else
                        echo "==> Gitleaks ${GITLEAKS_VERSION} already at ${GITLEAKS_DIR}, skipping"
                    fi
                    ${GITLEAKS_BIN} version
                """
            }

            stage('Prepare Reports') {
                sh "mkdir -p ${REPORT_DIR}"
            }

            stage('Credential Scanning') {
                sh """
                    set -e
                    echo "==> Starting credential scan on repository..."
                    echo "==> Repo    : ${repoUrl}"
                    echo "==> Branch  : ${branch}"

                    ${GITLEAKS_BIN} detect \
                        --source=. \
                        --report-format=json \
                        --report-path="${REPORT_DIR}/gitleaks-report.json" \
                        --redact \
                        --verbose \
                        --no-git \
                        2>&1 | tee "${REPORT_DIR}/gitleaks.log" || true

                    echo "==> Scan complete. Checking results..."

                    if [ -s "${REPORT_DIR}/gitleaks-report.json" ]; then
                        LEAK_COUNT=\$(cat "${REPORT_DIR}/gitleaks-report.json" | grep -c '"RuleID"' || echo 0)
                        echo "==> Total leaks found: \${LEAK_COUNT}"

                        if [ "\${LEAK_COUNT}" -gt "0" ]; then
                            echo "==> CREDENTIAL LEAKS DETECTED — see report for details"
                            echo "==> Report saved to ${REPORT_DIR}/gitleaks-report.json"
                        else
                            echo "==> No credential leaks found"
                        fi
                    else
                        echo "==> No leaks found — report is empty"
                        echo "[]" > "${REPORT_DIR}/gitleaks-report.json"
                    fi
                """
            }

            stage('Parse & Display Results') {
                sh """
                    echo "========================================"
                    echo "        GITLEAKS SCAN SUMMARY          "
                    echo "========================================"

                    if [ -s "${REPORT_DIR}/gitleaks-report.json" ]; then
                        echo "==> Files with leaked credentials:"
                        grep '"File"' "${REPORT_DIR}/gitleaks-report.json" | sort -u || true

                        echo ""
                        echo "==> Rule IDs triggered:"
                        grep '"RuleID"' "${REPORT_DIR}/gitleaks-report.json" | sort | uniq -c | sort -rn || true

                        echo ""
                        TOTAL=\$(grep -c '"RuleID"' "${REPORT_DIR}/gitleaks-report.json" || echo 0)
                        echo "==> TOTAL SECRETS FOUND: \${TOTAL}"
                    else
                        echo "==> Clean scan — no secrets detected"
                    fi

                    echo "========================================"
                """
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
