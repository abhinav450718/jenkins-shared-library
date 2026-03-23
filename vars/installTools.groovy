def call() {
    stage('Install Security Tools') {
        sh '''
        set -e

        echo "Installing Gitleaks..."
        wget https://github.com/gitleaks/gitleaks/releases/download/v8.18.0/gitleaks_8.18.0_linux_x64.tar.gz
        tar -xvzf gitleaks_8.18.0_linux_x64.tar.gz
        sudo mv gitleaks /usr/local/bin/

        echo "Installing Trivy..."
        sudo apt-get update -y
        sudo apt-get install -y wget apt-transport-https gnupg lsb-release

        wget -qO - https://aquasecurity.github.io/trivy-repo/deb/public.key | sudo apt-key add -
        echo "deb https://aquasecurity.github.io/trivy-repo/deb $(lsb_release -sc) main" | sudo tee /etc/apt/sources.list.d/trivy.list

        sudo apt-get update -y
        sudo apt-get install -y trivy

        gitleaks version
        trivy --version
        '''
    }
}
