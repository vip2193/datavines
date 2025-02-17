/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.datavines.engine.local.api;

import io.datavines.common.config.enums.SinkType;
import io.datavines.common.config.enums.SourceType;
import io.datavines.common.config.enums.TransformType;
import io.datavines.common.exception.DataVinesException;
import io.datavines.common.utils.StringUtils;
import io.datavines.engine.api.env.Execution;
import io.datavines.engine.local.api.entity.ResultList;
import io.datavines.engine.local.api.utils.LoggerFactory;
import io.datavines.engine.local.api.utils.SqlUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static io.datavines.common.ConfigConstants.*;
import static io.datavines.engine.api.EngineConstants.PLUGIN_TYPE;

public class LocalExecution implements Execution<LocalSource, LocalTransform, LocalSink> {

    private final Logger log = LoggerFactory.getLogger(LocalExecution.class);

    private final LocalRuntimeEnvironment localRuntimeEnvironment;

    public LocalExecution(LocalRuntimeEnvironment localRuntimeEnvironment){
        this.localRuntimeEnvironment = localRuntimeEnvironment;
    }

    @Override
    public void prepare() throws Exception {

    }

    @Override
    public void execute(List<LocalSource> sources, List<LocalTransform> transforms, List<LocalSink> sinks) throws Exception {
        if (CollectionUtils.isEmpty(sources)) {
            return;
        }

        List<String> invalidItemTableSet = new ArrayList<>();
        String preSql = null;
        String postSql = null;
        try {
            for (LocalSource localSource : sources)  {
                switch (SourceType.of(localSource.getConfig().getString(PLUGIN_TYPE))){
                    case SOURCE:
                        if (localRuntimeEnvironment.getSourceConnection() != null) {
                            break;
                        }

                        localRuntimeEnvironment.setSourceConnection(localSource.getConnectionItem(localRuntimeEnvironment));
                        if (!localSource.checkTableExist()) {
                            throw new DataVinesException("source table is not exist");
                        }

                        preSql = localSource.getConfig().getString(PRE_SQL);
                        postSql = localSource.getConfig().getString(POST_SQL);
                        try {
                            executeScript(preSql, localRuntimeEnvironment.getSourceConnection().getConnection());
                        } catch (SQLException e) {
                            throw new DataVinesException(e);
                        }

                        break;
                    case TARGET:
                        if (localRuntimeEnvironment.getTargetConnection() != null) {
                            break;
                        }

                        localRuntimeEnvironment.setTargetConnection(localSource.getConnectionItem(localRuntimeEnvironment));
                        if (!localSource.checkTableExist()) {
                            throw new DataVinesException("target table is not exist");
                        }

                        preSql = localSource.getConfig().getString(PRE_SQL);
                        postSql = localSource.getConfig().getString(POST_SQL);
                        try {
                            executeScript(preSql, localRuntimeEnvironment.getTargetConnection().getConnection());
                        } catch (SQLException e) {
                            throw new DataVinesException(e);
                        }

                        break;
                    case METADATA:
                        if (localRuntimeEnvironment.getMetadataConnection() != null) {
                            break;
                        }

                        localRuntimeEnvironment.setMetadataConnection(localSource.getConnectionItem(localRuntimeEnvironment));
                        break;
                    default:
                        break;
                }
            }

            List<ResultList> taskResult = new ArrayList<>();
            List<ResultList> actualValue = new ArrayList<>();
            transforms.forEach(localTransform -> {
                switch (TransformType.of(localTransform.getConfig().getString(PLUGIN_TYPE))){
                    case INVALIDATE_ITEMS:
                        if (StringUtils.isNotEmpty(localTransform.getConfig().getString(INVALIDATE_ITEMS_TABLE))) {
                            invalidItemTableSet.add(localTransform.getConfig().getString(INVALIDATE_ITEMS_TABLE));
                        }
                        localTransform.process(localRuntimeEnvironment);
                        break;
                    case ACTUAL_VALUE:
                        ResultList actualValueResult = localTransform.process(localRuntimeEnvironment);
                        actualValue.add(actualValueResult);
                        taskResult.add(actualValueResult);
                        break;
                    case EXPECTED_VALUE_FROM_METADATA_SOURCE:
                    case EXPECTED_VALUE_FROM_SOURCE:
                    case EXPECTED_VALUE_FROM_TARGET_SOURCE:
                        ResultList expectedResult = localTransform.process(localRuntimeEnvironment);
                        taskResult.add(expectedResult);
                        break;
                    default:
                        break;
                }
            });

            for (LocalSink localSink : sinks) {
                switch (SinkType.of(localSink.getConfig().getString(PLUGIN_TYPE))){
                    case ERROR_DATA:
                        localSink.output(null, localRuntimeEnvironment);
                        break;
                    case ACTUAL_VALUE:
                    case PROFILE_VALUE:
                        localSink.output(actualValue, localRuntimeEnvironment);
                        break;
                    case VALIDATE_RESULT:
                        localSink.output(taskResult, localRuntimeEnvironment);
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            log.error("execute error", e);
            throw e;
        } finally {
            for (String invalidItemTable : invalidItemTableSet) {
                try {
                    SqlUtils.dropView(invalidItemTable, localRuntimeEnvironment.getSourceConnection().getConnection());
                } catch (SQLException sqlException) {
                    log.error("drop view error: ", sqlException);
                }
            }
        }

        post(postSql);

        localRuntimeEnvironment.close();
    }

    private void post(String postSql) {
        try {
            if (localRuntimeEnvironment.getSourceConnection() != null) {
                executeScript(postSql, localRuntimeEnvironment.getSourceConnection().getConnection());
            }
        } catch (SQLException e) {
            throw new DataVinesException(e);
        }

        try {
            if (localRuntimeEnvironment.getTargetConnection() != null) {
                executeScript(postSql, localRuntimeEnvironment.getTargetConnection().getConnection());
            }
        } catch (SQLException e) {
            throw new DataVinesException(e);
        }
    }

    @Override
    public void stop() throws Exception {
        localRuntimeEnvironment.close();
    }

    private void executeScript(String script, Connection connection) {
        if (StringUtils.isNotEmpty(script) && !"null".equalsIgnoreCase(script)) {
            try (Statement statement = connection.createStatement()) {
                log.info("execute script: {}", script);
                statement.execute(script);
            } catch (SQLException e) {
                log.error("execute script error", e);
            }
        }
    }
}
