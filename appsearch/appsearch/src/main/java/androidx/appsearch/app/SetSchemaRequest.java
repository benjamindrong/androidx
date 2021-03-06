/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.appsearch.app;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.appsearch.exceptions.AppSearchException;
import androidx.collection.ArraySet;
import androidx.core.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Encapsulates a request to update the schema of an {@link AppSearchManager} database.
 *
 * @see AppSearchManager#setSchema
 */
public final class SetSchemaRequest {
    private final Set<AppSearchSchema> mSchemas;
    private final boolean mForceOverride;

    SetSchemaRequest(Set<AppSearchSchema> schemas, boolean forceOverride) {
        mSchemas = schemas;
        mForceOverride = forceOverride;
    }

    /** @hide */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    @NonNull
    public Set<AppSearchSchema> getSchemas() {
        return mSchemas;
    }

    /** @hide */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean isForceOverride() {
        return mForceOverride;
    }

    /** Builder for {@link SetSchemaRequest} objects. */
    public static final class Builder {
        private final Set<AppSearchSchema> mSchemas = new ArraySet<>();
        private boolean mForceOverride = false;
        private boolean mBuilt = false;

        /** Adds one or more types to the schema. */
        @NonNull
        public Builder addSchema(@NonNull AppSearchSchema... schemas) {
            Preconditions.checkNotNull(schemas);
            return addSchema(Arrays.asList(schemas));
        }

        /** Adds one or more types to the schema. */
        @NonNull
        public Builder addSchema(@NonNull Collection<AppSearchSchema> schemas) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(schemas);
            mSchemas.addAll(schemas);
            return this;
        }

        /**
         * Adds one or more types to the schema.
         *
         * @param dataClasses classes annotated with
         *                    {@link androidx.appsearch.annotation.AppSearchDocument}.
         * @throws AppSearchException if {@link androidx.appsearch.compiler.AppSearchCompiler}
         *                            has not generated a schema for the given data classes.
         */
        @NonNull
        public Builder addDataClass(@NonNull Class<?>... dataClasses)
                throws AppSearchException {
            Preconditions.checkNotNull(dataClasses);
            return addDataClass(Arrays.asList(dataClasses));
        }

        /**
         * Adds one or more types to the schema.
         *
         * @param dataClasses classes annotated with
         *                    {@link androidx.appsearch.annotation.AppSearchDocument}.
         * @throws AppSearchException if {@link androidx.appsearch.compiler.AppSearchCompiler}
         *                            has not generated a schema for the given data classes.
         */
        @NonNull
        public Builder addDataClass(@NonNull Collection<Class<?>> dataClasses)
                throws AppSearchException {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(dataClasses);
            List<AppSearchSchema> schemas = new ArrayList<>(dataClasses.size());
            DataClassFactoryRegistry registry = DataClassFactoryRegistry.getInstance();
            for (Class<?> dataClass : dataClasses) {
                DataClassFactory<?> factory = registry.getOrCreateFactory(dataClass);
                schemas.add(factory.getSchema());
            }
            return addSchema(schemas);
        }

        /**
         * Configures the {@link SetSchemaRequest} to delete any existing documents that don't
         * follow the new schema.
         *
         * <p>By default, this is {@code false} and schema incompatibility causes the
         * {@link AppSearchManager#setSchema} call to fail.
         *
         * @see AppSearchManager#setSchema
         */
        @NonNull
        public Builder setForceOverride(boolean forceOverride) {
            mForceOverride = forceOverride;
            return this;
        }

        /** Builds a new {@link SetSchemaRequest}. */
        @NonNull
        public SetSchemaRequest build() {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mBuilt = true;
            return new SetSchemaRequest(mSchemas, mForceOverride);
        }
    }
}
