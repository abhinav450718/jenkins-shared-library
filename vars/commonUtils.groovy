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

def createDir(path) {
    sh "mkdir -p ${path}"
}

def archiveReports(path) {
    archiveArtifacts artifacts: "${path}/**", fingerprint: true
}

def sendSlack(channel, message, status = "SUCCESS") {

    def colorMap = [
        'SUCCESS' : 'good',
        'UNSTABLE': 'warning',
        'FAILURE' : 'danger'
    ]

    slackSend(
        channel: channel,
        color  : colorMap.get(status, 'danger'),
        message: message
    )
}

def sendEmail(email, subject, body, attachmentPath = '') {

    if (!email) return

    emailext(
        to: email,
        subject: subject,
        body: body,
        mimeType: 'text/html',
        attachmentsPattern: attachmentPath ?: ''
    )
}

def getSarifFindings(reportPath) {
    try {
        return sh(
            script: "grep -c '\"ruleId\"' ${reportPath} 2>/dev/null || echo 0",
            returnStdout: true
        ).trim()
    } catch (e) {
        return "0"
    }
}

def buildStatusMessage(toolName, status, extraInfo = '') {

    return """
*${status}* - ${toolName}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Job: ${env.JOB_NAME}
Build: #${env.BUILD_NUMBER}
Status: ${status}
${extraInfo}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━
${env.BUILD_URL}
"""
}
