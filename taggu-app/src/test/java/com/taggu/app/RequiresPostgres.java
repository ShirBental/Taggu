package com.taggu.app;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Marks a test class that needs a real PostgreSQL; it is skipped where none can be had. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@ExtendWith(RequiresPostgres.Condition.class)
@interface RequiresPostgres {

    final class Condition implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            String reason = PostgresTestDatabase.unavailableReason();
            return reason == null
                    ? ConditionEvaluationResult.enabled("PostgreSQL is available")
                    : ConditionEvaluationResult.disabled(reason);
        }
    }
}
