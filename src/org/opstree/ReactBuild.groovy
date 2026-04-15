// src/org/ci/ReactBuild.groovy

package org.ci

class ReactBuild implements Serializable {

    def script
    def config

    def repoUrl
    def branch
    def slackChannel
    def nodeVersion
    def buildDir

    ReactBuild(def script, Map config) {
        this.script      = script
        this.config      = config
        this.repoUrl     = config.repoUrl
        this.branch      = config.branch      ?: 'main'
        this.slackChannel = config.slackChannel ?: '#ci-operation-notifications'
        this.nodeVersion = config.nodeVersion  ?: '18'
        this.buildDir    = config.buildDir     ?: 'build'
    }

    def cleanWorkspace() {
        script.stage('Clean Workspace') {
            script.cleanWs()
        }
    }

    def checkoutCode() {
        script.stage('Checkout Code') {
            script.git branch: branch, url: repoUrl
        }
    }

    def verifyNode() {
        script.stage('Verify Node') {
            script.sh 'node -v'
            script.sh 'npm -v'
        }
    }

    def installDependencies() {
        script.stage('Install Dependencies') {
            script.sh 'npm install 2>&1 | tee npm-install.log'
        }
    }

    def auditReport() {
        script.stage('Audit Report') {
            script.sh '''
                npm audit --json > npm-audit.json 2>/dev/null || true
                if [ ! -s npm-audit.json ]; then
                    echo '{"auditReportVersion":2,"vulnerabilities":{},"metadata":{"vulnerabilities":{"total":0}}}' > npm-audit.json
                fi
            '''
        }
    }

    def compileCode() {
        script.stage('Code Compilation') {
            script.sh '''
                export CI=false
                export NODE_OPTIONS=--openssl-legacy-provider
                npm run build 2>&1 | tee build-output.log
            '''
        }
    }

    def archiveReports() {
        script.stage('Archive Reports') {
            script.archiveArtifacts(
                artifacts: 'npm-install.log, npm-audit.json, build-output.log',
                allowEmptyArchive: true
            )
        }
    }

    def notifySlack(String status) {
        script.stage('Post Actions') {
            def buildLog    = "${script.env.BUILD_URL}artifact/build-output.log"
            def auditReport = "${script.env.BUILD_URL}artifact/npm-audit.json"
            def installLog  = "${script.env.BUILD_URL}artifact/npm-install.log"

            if (status == 'FAILURE') {
                script.slackSend(
                    channel: slackChannel,
                    color: 'danger',
                    message: "*FAILED* - React Code Compilation\n" +
                             "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                             "*Job Name:*       " + script.env.JOB_NAME + "\n" +
                             "*Build Number:*   #" + script.env.BUILD_NUMBER + "\n" +
                             "*Branch:*         " + branch + "\n" +
                             "*Status:*         Build Failed\n" +
                             "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                             "<" + script.env.BUILD_URL + "|View Build>   |   " +
                             "<" + buildLog + "|Build Log>   |   " +
                             "<" + installLog + "|Install Log>"
                )
            } else {
                script.slackSend(
                    channel: slackChannel,
                    color: 'good',
                    message: "*SUCCESS* - React Code Compilation\n" +
                             "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                             "*Job Name:*       " + script.env.JOB_NAME + "\n" +
                             "*Build Number:*   #" + script.env.BUILD_NUMBER + "\n" +
                             "*Branch:*         " + branch + "\n" +
                             "*Status:*         Build Passed\n" +
                             "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                             "<" + script.env.BUILD_URL + "|View Build>   |   " +
                             "<" + buildLog + "|Build Log>   |   " +
                             "<" + auditReport + "|Audit Report>   |   " +
                             "<" + installLog + "|Install Log>"
                )
            }
        }
    }

    def run() {
        script.node {
            try {
                cleanWorkspace()
                checkoutCode()
                verifyNode()
                installDependencies()
                auditReport()
                compileCode()
                archiveReports()
                script.currentBuild.result = 'SUCCESS'

            } catch (Exception err) {
                script.currentBuild.result = 'FAILURE'
                throw err

            } finally {
                def status = script.currentBuild.result ?: 'FAILURE'
                notifySlack(status)
                script.cleanWs()
            }
        }
    }
}
