.PHONY: build test sim clean guide web
MVN ?= mvn -q -B
guide:
	python3 tools/build-docs.py
web: guide
	cd empire-web && npm ci --silent && npm run build --silent
build: web
	$(MVN) -DskipTests package
test:
	$(MVN) test
# make sim PRESET=teaching UPDATES=60 COUNTRIES=4 SEED=1
PRESET ?= teaching
UPDATES ?= 60
COUNTRIES ?= 4
SEED ?= 1
sim: build
	java -jar empire-sim/target/empire-sim.jar --preset $(PRESET) --updates $(UPDATES) --countries $(COUNTRIES) --seed $(SEED) --out sim-out
clean:
	$(MVN) clean
