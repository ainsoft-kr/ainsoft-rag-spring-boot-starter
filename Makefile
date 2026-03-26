.PHONY: boot-run-local publish-engine publish-autoconfigure start-falkordb

publish-engine:
	cd ../ainsoft-rag-engine && ./gradlew clean publishToMavenLocal

publish-autoconfigure:
	cd ../ainsoft-rag-spring-boot-autoconfigure && ./gradlew clean publishToMavenLocal

start-falkordb:
	docker stack deploy -c ./examples/spring-boot-demo/deploy/stack.falkordb.yml infra

boot-run-local: publish-engine publish-autoconfigure
	./gradlew :spring-boot-demo:bootRun --args='--spring.profiles.active=local'
