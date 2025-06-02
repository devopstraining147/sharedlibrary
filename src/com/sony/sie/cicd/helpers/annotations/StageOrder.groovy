
package com.sony.sie.cicd.helpers.annotations

import java.lang.annotation.Retention
import static java.lang.annotation.RetentionPolicy.RUNTIME

// Change retention policy to RUNTIME (default is CLASS)
@Retention(RUNTIME)
@interface StageOrder {
   int id()
}
