## build

```bash
./gradlew clean shadowJar
cp build/libs/course-selection-all.jar .
```

## config set-up
```bash
./example.conf.sh '["student"]' 127.0.0.1 2552 127.0.0.1 2552 0 > config-student.conf
./example.conf.sh '["course"]' 127.0.0.1 2553 127.0.0.1 2552 0 > config-course.conf
./example.conf.sh '["http"]' 127.0.0.1 2554 127.0.0.1 2552 7000 > config-http.conf
```

## run

```bash
java -cp course-selection-all.jar -Dconfig.file=config-student.conf moe.taiho.course_selection.cluster.ClusterMain
java -cp course-selection-all.jar -Dconfig.file=config-course.conf moe.taiho.course_selection.cluster.ClusterMain
java -cp course-selection-all.jar -Dconfig.file=config-http.conf moe.taiho.course_selection.cluster.ClusterMain
```

