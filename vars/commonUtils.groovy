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

def sendEmail(email, subject, body, attachments = '') {

    if (!email) return

    emailext(
        to: email,
        subject: subject,
        body: body,
        mimeType: 'text/html',
        attachmentsPattern: attachments
    )
}
