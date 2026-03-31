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
                    else
                        echo "==> Gitleaks ${GITLEAKS_VERSION} already installed, skipping"
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
                    echo "==> Starting credential scan..."
                    echo "==> Repo   : ${repoUrl}"
                    echo "==> Branch : ${branch}"

                    ${GITLEAKS_BIN} detect \
                        --source=. \
                        --report-format=json \
                        --report-path="${REPORT_DIR}/gitleaks-report.json" \
                        --redact \
                        --verbose \
                        --no-git \
                        2>&1 | tee "${REPORT_DIR}/gitleaks.log" || true

                    echo "==> Scan complete"
                    echo "==> Total findings:"
                    grep -c '"RuleID"' "${REPORT_DIR}/gitleaks-report.json" || echo "0 findings"
                """
            }

            stage('Publish Report') {
                recordIssues(
                    tools: [
                        issues(
                            pattern: "${REPORT_DIR}/gitleaks-report.json",
                            name: 'Gitleaks',
                            id: 'gitleaks'
                        )
                    ],
                    qualityGates: [[
                        threshold: 1,
                        type: 'TOTAL',
                        unstable: true
                    ]],
                    name: 'Gitleaks Credential Scan',
                    skipPublishingChecks: true
                )
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
                def emoji  = (status == 'SUCCESS') ? '✅'    : '❌'
                slackSend(
                    channel: slackChannel,
                    color  : color,
                    message: """\
${emoji} *${status}* - Credential Scan | OT-Microservices
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
