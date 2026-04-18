def call(Map config) {

    node {

        def repoUrl    = config.repoUrl
        def branch     = config.branch ?: 'master'
        def credId     = config.gitCredentialsId ?: ''
        def slackCh    = config.slackChannel ?: '#ci-operation-notifications'
        def email      = config.email ?: ''
        def version    = config.gitleaksVersion ?: '8.18.2'
        def REPORT_DIR = "reports"

        def TOOLS_DIR  = "/var/lib/jenkins/tools"
        def GITLEAKS   = "${TOOLS_DIR}/gitleaks-${version}/gitleaks"

        try {

            stage('Clean Workspace') {
                commonUtils.cleanWorkspace()
            }

            stage('Checkout') {
                commonUtils.checkoutCode(repoUrl, branch, credId)
            }

            stage('Setup Gitleaks') {
                sh """
                    set -e
                    mkdir -p ${TOOLS_DIR}/gitleaks-${version}

                    if [ ! -f "${GITLEAKS}" ]; then
                        curl -sLO https://github.com/gitleaks/gitleaks/releases/download/v${version}/gitleaks_${version}_linux_x64.tar.gz
                        tar -xzf gitleaks_${version}_linux_x64.tar.gz -C ${TOOLS_DIR}/gitleaks-${version}
                        chmod +x ${GITLEAKS}
                    fi
                """
            }

            stage('Credential Scan') {
                commonUtils.createDir(REPORT_DIR)

                sh """
                    ${GITLEAKS} detect \
                        --source=. \
                        --report-format=sarif \
                        --report-path=${REPORT_DIR}/gitleaks-report.sarif \
                        --no-git || true

                    ${GITLEAKS} detect \
                        --source=. \
                        --report-format=csv \
                        --report-path=${REPORT_DIR}/gitleaks-report.csv \
                        --no-git || true
                """
            }

            stage('Archive Reports') {
                commonUtils.archiveReports(REPORT_DIR)
            }

            currentBuild.result = 'SUCCESS'

        } catch (err) {
            currentBuild.result = 'FAILURE'
            throw err

        } finally {

            def status = currentBuild.result ?: 'FAILURE'

            commonUtils.notifyBuild(
                status: status,
                toolName: "Gitleaks Credential Scan",
                branch: branch,
                slackChannel: slackCh,
                email: email,
                reports: [
                    "SARIF Report" : "${REPORT_DIR}/gitleaks-report.sarif",
                    "CSV Report"   : "${REPORT_DIR}/gitleaks-report.csv"
                ],
                attachments: "${REPORT_DIR}/**"
            )

            cleanWs()
        }
    }
}
