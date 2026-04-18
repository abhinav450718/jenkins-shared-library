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

def notifyBuild(Map config = [:]) {

    def status   = config.status ?: 'SUCCESS'
    def toolName = config.toolName ?: 'Pipeline'
    def branch   = config.branch ?: 'N/A'
    def email    = config.email ?: ''
    def channel  = config.slackChannel ?: '#ci-operation-notifications'
    def reports  = config.reports ?: [:]

    def buildUrl = env.BUILD_URL

    def colorMap = [
        'SUCCESS' : 'good',
        'UNSTABLE': 'warning',
        'FAILURE' : 'danger'
    ]

    def links = ""
    reports.each { name, path ->
        def fullUrl = "${buildUrl}artifact/${path}"
        links += "<${fullUrl}|${name}> | "
    }

    def slackMessage = """
*${status}* - ${toolName}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Job: ${env.JOB_NAME}
Build: #${env.BUILD_NUMBER}
Branch: ${branch}
Status: ${status}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━
<${buildUrl}|View Build> | ${links}
"""

    slackSend(
        channel: channel,
        color  : colorMap.get(status, 'danger'),
        message: slackMessage
    )

    if (email) {

        def emailLinks = ""
        reports.each { name, path ->
            def fullUrl = "${buildUrl}artifact/${path}"
            emailLinks += "<a href='${fullUrl}'>${name}</a> | "
        }

        def emailBody = """
<h3>${status} - ${toolName}</h3>
<p><b>Job:</b> ${env.JOB_NAME}</p>
<p><b>Build:</b> #${env.BUILD_NUMBER}</p>
<p><b>Branch:</b> ${branch}</p>
<p><b>Status:</b> ${status}</p>
<p>
<a href="${buildUrl}">View Build</a> |
${emailLinks}
</p>
"""

        emailext(
            to: email,
            subject: "${status}: ${toolName}",
            body: emailBody,
            mimeType: 'text/html',
            attachmentsPattern: config.attachments ?: ''
        )
    }
}
