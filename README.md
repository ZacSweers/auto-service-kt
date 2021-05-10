# auto-service-kt

A Kotlin compiler plugin implementation of [AutoService](https://github.com/google/auto/tree/master/service).

## Usage

Simply add the auto-service-kt Gradle Plugin.

[![Maven Central](https://img.shields.io/maven-central/v/dev.zacsweers.autoservice/gradle-plugin.svg)](https://mvnrepository.com/artifact/dev.zacsweers.autoservice/gradle-plugin)
```kotlin
plugins {
  id("dev.zacsweers.autoservice") version "<version>"
  // ...
}

// Optional if you want to force a newer AutoService annotations version
autoService {
  annotationsVersion.set("1.0")
}
```

Then annotate your service implementation with `@AutoService` as your normally would in source 
files.

## Caveats

While the AutoService _annotation processor_ will merge existing service files, but this is not
currently implemented in this plugin yet.

AutoService's `verify` and `verbose` options are also not yet implemented.

Snapshots of the development version are available in [Sonatype's `snapshots` repository][snap].

License
--------

    Copyright 2021 Zac Sweers

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.


[snap]: https://oss.sonatype.org/content/repositories/snapshots/dev/zacsweers/autoservice/
