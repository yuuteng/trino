/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.accumulo;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import io.trino.plugin.accumulo.model.AccumuloColumnConstraint;
import io.trino.plugin.accumulo.model.AccumuloColumnHandle;
import io.trino.plugin.accumulo.model.AccumuloSplit;
import io.trino.plugin.accumulo.model.AccumuloTableHandle;
import io.trino.plugin.accumulo.model.SerializedRange;
import io.trino.plugin.accumulo.model.TabletSplitMetadata;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorSplit;
import io.trino.spi.connector.ConnectorSplitManager;
import io.trino.spi.connector.ConnectorSplitSource;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.DynamicFilter;
import io.trino.spi.connector.FixedSplitSource;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.TupleDomain;

import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

public class AccumuloSplitManager
        implements ConnectorSplitManager
{
    private final AccumuloClient client;

    @Inject
    public AccumuloSplitManager(AccumuloClient client)
    {
        this.client = requireNonNull(client, "client is null");
    }

    @Override
    public ConnectorSplitSource getSplits(
            ConnectorTransactionHandle transactionHandle,
            ConnectorSession session,
            ConnectorTableHandle tableHandle,
            DynamicFilter dynamicFilter,
            Constraint constraint)
    {
        AccumuloTableHandle handle = (AccumuloTableHandle) tableHandle;

        String schemaName = handle.getSchema();
        String tableName = handle.getTable();
        String rowIdName = handle.getRowId();

        // Get non-row ID column constraints
        List<AccumuloColumnConstraint> constraints = getColumnConstraints(rowIdName, handle.getConstraint());

        // Get the row domain column range
        Optional<Domain> rDom = getRangeDomain(rowIdName, handle.getConstraint());

        // Call out to our client to retrieve all tablet split metadata using the row ID domain and the secondary index
        List<TabletSplitMetadata> tabletSplits = client.getTabletSplits(session, schemaName, tableName, rDom, constraints, handle.getSerializerInstance());

        // Pack the tablet split metadata into a connector split
        ImmutableList.Builder<ConnectorSplit> cSplits = ImmutableList.builder();
        for (TabletSplitMetadata splitMetadata : tabletSplits) {
            AccumuloSplit split = new AccumuloSplit(
                    splitMetadata.getRanges().stream().map(SerializedRange::serialize).collect(Collectors.toList()),
                    splitMetadata.getHostPort());
            cSplits.add(split);
        }

        return new FixedSplitSource(cSplits.build());
    }

    private static Optional<Domain> getRangeDomain(String rowIdName, TupleDomain<ColumnHandle> constraint)
    {
        if (constraint.getDomains().isPresent()) {
            for (Entry<ColumnHandle, Domain> columnDomain : constraint.getDomains().get().entrySet()) {
                AccumuloColumnHandle col = (AccumuloColumnHandle) columnDomain.getKey();
                if (col.getName().equals(rowIdName)) {
                    return Optional.of(columnDomain.getValue());
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Gets a list of {@link AccumuloColumnConstraint} based on the given constraint ID, excluding the row ID column
     *
     * @param rowIdName Trino column name mapping to the Accumulo row ID
     * @param constraint Set of query constraints
     * @return List of all column constraints
     */
    private static List<AccumuloColumnConstraint> getColumnConstraints(String rowIdName, TupleDomain<ColumnHandle> constraint)
    {
        ImmutableList.Builder<AccumuloColumnConstraint> constraintBuilder = ImmutableList.builder();
        constraint.getDomains().orElseThrow().forEach((handle, domain) -> {
            AccumuloColumnHandle columnHandle = (AccumuloColumnHandle) handle;

            if (!columnHandle.getName().equals(rowIdName)) {
                // Family and qualifier will exist for non-row ID columns
                constraintBuilder.add(new AccumuloColumnConstraint(
                        columnHandle.getName(),
                        columnHandle.getFamily().get(),
                        columnHandle.getQualifier().get(),
                        Optional.of(domain),
                        columnHandle.isIndexed()));
            }
        });

        return constraintBuilder.build();
    }
}
