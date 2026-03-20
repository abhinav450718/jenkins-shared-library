def call(Map config = [:]) {

    def appPath = config.path ?: '.'

    echo "Starting Golang CI (Build + Test)"

    sh """
    set -e

    echo "Checking Go installation..."
    go version

    cd ${appPath}

    echo "Cleaning and preparing modules..."
    go mod tidy
    go mod download

    echo "Running unit tests..."
    go test ./... -v

    echo "Running build..."
    go build ./...

    echo "Golang CI completed successfully"
    """
}
