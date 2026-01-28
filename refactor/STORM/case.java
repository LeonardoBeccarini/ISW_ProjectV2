// It only needs to be parseable by JavaParser for metric extraction.

// ======= BEFORE (original) =======

public List<List<Column>> select_before(String sqlQuery, List<Column> queryParams) {
    Connection connection = null;
    try {
        connection = connectionProvider.getConnection();
        PreparedStatement preparedStatement = connection.prepareStatement(sqlQuery);
        if(queryTimeoutSecs > 0) {
            preparedStatement.setQueryTimeout(queryTimeoutSecs);
        }
        setPreparedStatementParams(preparedStatement, queryParams);
        ResultSet resultSet = preparedStatement.executeQuery();
        List<List<Column>> rows = Lists.newArrayList();
        while(resultSet.next()){
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();
            List<Column> row = Lists.newArrayList();
            for(int i=1 ; i <= columnCount; i++) {
                String columnLabel = metaData.getColumnLabel(i);
                int columnType = metaData.getColumnType(i);
                Class columnJavaType = Util.getJavaType(columnType);
                if (columnJavaType.equals(String.class)) {
                    row.add(new Column<String>(columnLabel, resultSet.getString(columnLabel), columnType));
                } else if (columnJavaType.equals(Integer.class)) {
                    row.add(new Column<Integer>(columnLabel, resultSet.getInt(columnLabel), columnType));
                } else if (columnJavaType.equals(Double.class)) {
                    row.add(new Column<Double>(columnLabel, resultSet.getDouble(columnLabel), columnType));
                } else if (columnJavaType.equals(Float.class)) {
                    row.add(new Column<Float>(columnLabel, resultSet.getFloat(columnLabel), columnType));
                } else if (columnJavaType.equals(Short.class)) {
                    row.add(new Column<Short>(columnLabel, resultSet.getShort(columnLabel), columnType));
                } else if (columnJavaType.equals(Boolean.class)) {
                    row.add(new Column<Boolean>(columnLabel, resultSet.getBoolean(columnLabel), columnType));
                } else if (columnJavaType.equals(byte[].class)) {
                    row.add(new Column<byte[]>(columnLabel, resultSet.getBytes(columnLabel), columnType));
                } else if (columnJavaType.equals(Long.class)) {
                    row.add(new Column<Long>(columnLabel, resultSet.getLong(columnLabel), columnType));
                } else if (columnJavaType.equals(Date.class)) {
                    row.add(new Column<Date>(columnLabel, resultSet.getDate(columnLabel), columnType));
                } else if (columnJavaType.equals(Time.class)) {
                    row.add(new Column<Time>(columnLabel, resultSet.getTime(columnLabel), columnType));
                } else if (columnJavaType.equals(Timestamp.class)) {
                    row.add(new Column<Timestamp>(columnLabel, resultSet.getTimestamp(columnLabel), columnType));
                } else {
                    throw new RuntimeException("type =  " + columnType + " for column " + columnLabel + " not supported.");
                }
            }
            rows.add(row);
        }
        return rows;
    } catch (SQLException e) {
        throw new RuntimeException("Failed to execute select query " + sqlQuery, e);
    } finally {
        closeConnection(connection);
    }
}

// ======= AFTER (refactored) =======

public List<List<Column>> select_after(String sqlQuery, List<Column> queryParams) {
    Connection connection = null;
    try {
        connection = connectionProvider.getConnection();
        PreparedStatement preparedStatement = prepareStatement(connection, sqlQuery, queryParams);
        ResultSet resultSet = preparedStatement.executeQuery();
        return readRows(resultSet);
    } catch (SQLException e) {
        throw new RuntimeException("Failed to execute select query " + sqlQuery, e);
    } finally {
        closeConnection(connection);
    }
}

private PreparedStatement prepareStatement(Connection connection, String sqlQuery, List<Column> queryParams)
        throws SQLException {
    PreparedStatement preparedStatement = connection.prepareStatement(sqlQuery);
    if (queryTimeoutSecs > 0) {
        preparedStatement.setQueryTimeout(queryTimeoutSecs);
    }
    setPreparedStatementParams(preparedStatement, queryParams);
    return preparedStatement;
}

private List<List<Column>> readRows(ResultSet resultSet) throws SQLException {
    List<List<Column>> rows = Lists.newArrayList();

    ResultSetMetaData metaData = resultSet.getMetaData();
    int columnCount = metaData.getColumnCount();

    while (resultSet.next()) {
        List<Column> row = Lists.newArrayList();
        for (int i = 1; i <= columnCount; i++) {
            row.add(readColumn(resultSet, metaData, i));
        }
        rows.add(row);
    }

    return rows;
}

private Column<?> readColumn(ResultSet resultSet, ResultSetMetaData metaData, int index) throws SQLException {
    String columnLabel = metaData.getColumnLabel(index);
    int columnType = metaData.getColumnType(index);
    Class columnJavaType = Util.getJavaType(columnType);

    if (columnJavaType.equals(String.class)) {
        return new Column<String>(columnLabel, resultSet.getString(columnLabel), columnType);
    }
    if (columnJavaType.equals(Integer.class)) {
        return new Column<Integer>(columnLabel, resultSet.getInt(columnLabel), columnType);
    }
    if (columnJavaType.equals(Double.class)) {
        return new Column<Double>(columnLabel, resultSet.getDouble(columnLabel), columnType);
    }
    if (columnJavaType.equals(Float.class)) {
        return new Column<Float>(columnLabel, resultSet.getFloat(columnLabel), columnType);
    }
    if (columnJavaType.equals(Short.class)) {
        return new Column<Short>(columnLabel, resultSet.getShort(columnLabel), columnType);
    }
    if (columnJavaType.equals(Boolean.class)) {
        return new Column<Boolean>(columnLabel, resultSet.getBoolean(columnLabel), columnType);
    }
    if (columnJavaType.equals(byte[].class)) {
        return new Column<byte[]>(columnLabel, resultSet.getBytes(columnLabel), columnType);
    }
    if (columnJavaType.equals(Long.class)) {
        return new Column<Long>(columnLabel, resultSet.getLong(columnLabel), columnType);
    }
    if (columnJavaType.equals(Date.class)) {
        return new Column<Date>(columnLabel, resultSet.getDate(columnLabel), columnType);
    }
    if (columnJavaType.equals(Time.class)) {
        return new Column<Time>(columnLabel, resultSet.getTime(columnLabel), columnType);
    }
    if (columnJavaType.equals(Timestamp.class)) {
        return new Column<Timestamp>(columnLabel, resultSet.getTimestamp(columnLabel), columnType);
    }

    throw new RuntimeException("type =  " + columnType + " for column " + columnLabel + " not supported.");
}
