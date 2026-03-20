def call(Map config = [:]) {

    def appPath = config.path ?: '.'
    def binaryName = config.binary ?: 'app-binary'
    def testReport = config.testReport ?: 'test-report.json'

    echo "Starting Golang CI (Build + Test with Artifacts)"

    sh """
    set -e

    echo "Checking Go installation..."
    go version

    cd ${appPath}

    echo "Preparing Go modules..."
    go mod tidy
    go mod download

    echo "Running unit tests (capturing report)..."
    go test ./... -v -json > ${testReport} || echo "Some tests failed, continuing..."

    echo "Building application binary..."
    go build -o ${binaryName} .

    echo "Artifacts generated:"
    ls -lh ${binaryName} ${testReport}
    """

    echo "Archiving artifacts..."

    archiveArtifacts artifacts: "${binaryName}, ${testReport}", fingerprint: true
}
