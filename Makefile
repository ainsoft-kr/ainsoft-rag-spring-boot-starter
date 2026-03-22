.PHONY: boot-run-local

boot-run-local:
	./gradlew :spring-boot-demo:bootRun --args='--spring.profiles.active=local'
