def call(Map config) {
    node {
        def repoUrl          = config.repoUrl
        def branch           = config.branch           ?: 'master'
        def gitCredentialsId = config.gitCredentialsId ?: ''
        def slackChannel     = config.slackChannel     ?: '#ci-operation-notifications'
        def email            = config.email            ?: ''
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
                        mkdir -p ${GITLEAKS_DIR}
                        curl -sLO https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz
                        tar -xzf gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz -C ${GITLEAKS_DIR}
                        rm -f gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz
                        chmod +x ${GITLEAKS_BIN}
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
                    ${GITLEAKS_BIN} detect \\
                        --source=. \\
                        --report-format=sarif \\
                        --report-path="${REPORT_DIR}/gitleaks-report.sarif" \\
                        --redact \\
                        --no-git \\
                        2>&1 || true

                    ${GITLEAKS_BIN} detect \\
                        --source=. \\
                        --report-format=csv \\
                        --report-path="${REPORT_DIR}/gitleaks-report.csv" \\
                        --redact \\
                        --no-git \\
                        2>&1 | tee "${REPORT_DIR}/gitleaks.log" || true
                """
            }

            stage('Publish Warnings Report') {
                recordIssues(
                    tools: [
                        sarif(
                            pattern: "${REPORT_DIR}/gitleaks-report.sarif",
                            name   : 'Gitleaks',
                            id     : 'gitleaks'
                        )
                    ],
                    name                : 'Gitleaks Credential Scan',
                    skipPublishingChecks: true
                )
            }

            stage('Archive Reports') {
                archiveArtifacts artifacts: "${REPORT_DIR}/**", fingerprint: true
            }

            currentBuild.result = 'SUCCESS'

        } catch (err) {
            currentBuild.result = 'FAILURE'
            throw err

        } finally {
            stage('Post Actions') {
                def status = currentBuild.result ?: 'FAILURE'

                def colorMap = [
                    'SUCCESS' : 'good',
                    'UNSTABLE': 'warning',
                    'FAILURE' : 'danger'
                ]

                def color = colorMap.get(status, 'danger')

                def findings = '0'
                try {
                    findings = sh(
                        script: "grep -c '\"ruleId\"' ${REPORT_DIR}/gitleaks-report.sarif 2>/dev/null || echo 0",
                        returnStdout: true
                    ).trim()
                } catch (ignored) { }

                def sarifReport = "${env.BUILD_URL}artifact/${REPORT_DIR}/gitleaks-report.sarif"
                def csvReport   = "${env.BUILD_URL}artifact/${REPORT_DIR}/gitleaks-report.csv"

                // Slack Notification
                slackSend(
                    channel: slackChannel,
                    color  : color,
                    message: "*${status}* - Gitleaks Credential Scan\n" +
                             "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                             "*Job Name:*       ${env.JOB_NAME}\n" +
                             "*Build Number:*   #${env.BUILD_NUMBER}\n" +
                             "*Branch:*         ${branch}\n" +
                             "*Findings:*       ${findings} secret(s) detected\n" +
                             "*Status:*         ${status}\n" +
                             "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                             "<${env.BUILD_URL}|View Build>   |   " +
                             "<${sarifReport}|SARIF Report>   |   " +
                             "<${csvReport}|CSV Report>"
                )

                // Email Notification
                if (email) {
                    emailext(
                        to: email,
                        subject: "${status}: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                        body: """
                        <h3>${status} - Gitleaks Credential Scan</h3>
                        <p><b>Job Name:</b> ${env.JOB_NAME}</p>
                        <p><b>Build Number:</b> #${env.BUILD_NUMBER}</p>
                        <p><b>Branch:</b> ${branch}</p>
                        <p><b>Findings:</b> ${findings} secret(s)</p>
                        <p><b>Status:</b> ${status}</p>
                        <p>
                            <a href="${env.BUILD_URL}">View Build</a> |
                            <a href="${sarifReport}">SARIF Report</a> |
                            <a href="${csvReport}">CSV Report</a>
                        </p>
                        """,
                        mimeType: 'text/html',
                        attachmentsPattern: "${REPORT_DIR}/**"
                    )
                }

                cleanWs()
            }
        }
    }
}
