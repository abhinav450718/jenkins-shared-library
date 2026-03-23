def call(reportDir) {
    stage('License Scan') {
        sh """
        mkdir -p ${reportDir}

        trivy fs \
        --scanners license \
        --format table \
        --output ${reportDir}/trivy-license-report.txt \
        .
        """
    }
}
