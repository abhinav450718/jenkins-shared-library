def cleanWorkspace() {
    cleanWs()
}

def checkoutCode(repoUrl, branch, credId = '') {
    if (credId?.trim()) {
        git url: repoUrl, branch: branch, credentialsId: credId
    } else {
        git url: repoUrl, branch: branch
    }
}

def sendSlackNotification(channel, status, branch, findings, reportDir) {

    def colorMap = [
        'SUCCESS' : 'good',
        'UNSTABLE': 'warning',
        'FAILURE' : 'danger'
    ]

    def color = colorMap.get(status, 'danger')

    def sarifReport = "${env.BUILD_URL}artifact/${reportDir}/gitleaks-report.sarif"
    def csvReport   = "${env.BUILD_URL}artifact/${reportDir}/gitleaks-report.csv"

    slackSend(
        channel: channel,
        color  : color,
        message: "*${status}* - Gitleaks Credential Scan\n" +
                 "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                 "*Job Name:* ${env.JOB_NAME}\n" +
                 "*Build:* #${env.BUILD_NUMBER}\n" +
                 "*Branch:* ${branch}\n" +
                 "*Findings:* ${findings}\n" +
                 "*Status:* ${status}\n" +
                 "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                 "<${env.BUILD_URL}|View Build> | <${sarifReport}|SARIF> | <${csvReport}|CSV>"
    )
}

def sendEmailNotification(email, status, branch, findings, reportDir) {

    if (!email) return

    def sarifReport = "${env.BUILD_URL}artifact/${reportDir}/gitleaks-report.sarif"
    def csvReport   = "${env.BUILD_URL}artifact/${reportDir}/gitleaks-report.csv"

    emailext(
        to: email,
        subject: "${status}: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
        body: """
        <h3>${status} - Gitleaks Scan</h3>
        <p><b>Branch:</b> ${branch}</p>
        <p><b>Findings:</b> ${findings}</p>
        <p><b>Status:</b> ${status}</p>
        <p>
            <a href="${env.BUILD_URL}">Build</a> |
            <a href="${sarifReport}">SARIF</a> |
            <a href="${csvReport}">CSV</a>
        </p>
        """,
        mimeType: 'text/html',
        attachmentsPattern: "${reportDir}/**"
    )
}

def getFindings(reportDir) {
    try {
        return sh(
            script: "grep -c '\"ruleId\"' ${reportDir}/gitleaks-report.sarif 2>/dev/null || echo 0",
            returnStdout: true
        ).trim()
    } catch (e) {
        return "0"
    }
}
