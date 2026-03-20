def call(Map config = [:]) {

    def scanPath = config.path ?: '.'
    def reportFile = config.report ?: 'gitleaks-report.json'

    echo "Running Credential Scan using Gitleaks"

    sh """
    if ! command -v gitleaks > /dev/null; then
        echo "Installing Gitleaks..."
        wget -q https://github.com/gitleaks/gitleaks/releases/latest/download/gitleaks-linux-amd64 -O gitleaks
        chmod +x gitleaks
        sudo mv gitleaks /usr/local/bin/
    fi

    gitleaks detect \
    --source ${scanPath} \
    --report-format json \
    --report-path ${reportFile}
    """

    archiveArtifacts artifacts: reportFile, fingerprint: true
}
