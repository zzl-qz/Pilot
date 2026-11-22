package com.pilot.server;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/** Marks tests whose setup or assertions require loopback networking. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(WindowsJdkLoopbackCondition.class)
public @interface WindowsJdkLoopbackSensitive {
}
