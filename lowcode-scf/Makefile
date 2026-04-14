# Low-code stage developer tasks.
#
#   make docs   -- regenerate AsyncAPI HTML into ./public/
#   make build  -- build the bootJar (requires network-accessible Maven Central)
#   make image  -- docker build the runtime image

.PHONY: docs build image clean

docs:
	./generate-docs.sh

build:
	docker run --rm -v "$(PWD)":/app -w /app gradle:8.10-jdk17 \
	  gradle --no-daemon bootJar

image:
	docker build -t document-enricher:latest .

clean:
	rm -rf public build .gradle
