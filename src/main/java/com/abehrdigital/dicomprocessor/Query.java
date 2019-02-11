package com.abehrdigital.dicomprocessor;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.hibernate.transform.AliasToEntityMapResultTransformer;

import java.util.*;

public class Query {

    private String query;                           // resulting query
    private String dataSet; 						// table name
    private String XID;                             // encoding for current id, stored as key in Test.XID_map
    private String CRUD; 							// e.g. retrieve, create, merge
    private TreeMap<String, String> unknownFields;	// e.g. {name=>$$_tableXID.fieldName_$$, date=>$$_tableXID.fieldDate_$$, }
    private TreeMap<String, String> knownFields;	// e.g. {id=>5, class_name="Examination", episode_id=245, }
    private TreeMap<String, ForeignKey> foreignKeys;// e.g. {referencing_XID=>(referenced_column,referencing_column), }

    Query(String dataSet, String XID, String CRUD, TreeMap<String, String> knownFields, TreeMap<String, String> unknownFields,
          TreeMap<String, ForeignKey> foreignKeys ) {
        this.dataSet = dataSet;
        this.XID = XID;
        this.CRUD = CRUD;
        this.knownFields = knownFields;
        this.unknownFields = unknownFields;
        this.foreignKeys = foreignKeys;
    }

    @Override
    public String toString() {
        return "Query [dataSet=" + dataSet + ", CRUD=" + CRUD +
                ", \n\tunknownFields=" + unknownFields +
                ", \n\tknownFields=" + knownFields +
                ", \n\tforeignKeys=" + foreignKeys +
                "]\n";
    }

    // construct sql query
    int constructAndRunQuery() throws Exception {
        // before constructing the query, update the list of known and unknown fields
        updateKnownUnknown();

        String secondary_query_insert;
        int rows_affected = 0;

        switch (this.CRUD) {
            case "create":
                //TODO: create might not be permitted as CRUD operation:
                // does MERGE take its place?
                secondary_query_insert = constructInsertQuery();
                // execute query and the secondary query
                rows_affected = executeQuery(Test.getSession(), secondary_query_insert);
                System.out.println("ins rows: " + rows_affected);
                break;
            case "retrieve":
                secondary_query_insert = constructSelectQuery();
                // execute select query
                rows_affected = executeQuery(Test.getSession(), secondary_query_insert);
                System.out.println("sel rows: " + rows_affected);
                break;
            case "merge":
                // if id is known, try to update fields
                if (Test.XID_map.containsKey(XID) && Test.XID_map.get(XID) !=  null && Test.XID_map.get(XID).knownFields != null) {
                    System.out.println("Value is here");

                    secondary_query_insert = constructUpdateQuery();

                    this.CRUD = "create";

                    // when the ID is not known -> do queryByExample to retrieve it
                    // when the ID is known (present in the XID_map) -> update the rest of the columns
                    rows_affected = executeQuery(Test.getSession(), secondary_query_insert);
                    if (rows_affected != 1) {
                        throw new Exception("Could not update.");
                    }
                } else {
                    // no id, then try Retrieve.
                    //  if no rows are returned, then insert and get the newly introduced id
                    System.out.println("Value is not here");

                    this.CRUD = "retrieve";
                    secondary_query_insert = constructSelectQuery();

                    // execute select query
                    rows_affected = executeQuery(Test.getSession(), secondary_query_insert);

                    System.out.println("merge rows: " + rows_affected);

                    if (rows_affected == 0) {
                        // no rows returned: insert
                        this.CRUD = "create";

                        System.out.println("insert");
                        secondary_query_insert = constructInsertQuery();
                        System.out.println("Second: " + secondary_query_insert);
                        // execute query and the secondary query
                        rows_affected = executeQuery(Test.getSession(), secondary_query_insert);
                    } else if (rows_affected == 1) {
                        // records found
                        System.out.println("ONE Record found");
                    } else {
                        System.out.println("MORE THAN ONE record found");
                    }
                }

                // DEBUG purposes
                if (Test.XID_map.containsKey(XID) && Test.XID_map.get(XID) !=  null) {
                    Test.printMap("Value is here", Test.XID_map);
                } else {
                    Test.printMap("Value is not here", Test.XID_map);
                }

                break;
            default:
                break;
        }
        return rows_affected;
    }

    /**
     * add to the list of KnownFields those that have a value in XID_map and remove them from UnknownFields
     * add to the list of KnownFields the foreign keys that have a value in XID_map
     */
    private void updateKnownUnknown() throws Exception {
        Iterator itr = unknownFields.entrySet().iterator();

        /* search through all unknown fields and if the encoding "$$_..._$$" has a value in Test.XID_map,
         * add it to the known fields and remove it from the unknownFields
         */
        while(itr.hasNext()) {
            String id_unknown = ((Map.Entry<String, String>) itr.next()).getKey();
            String XID_unknown = unknownFields.get(id_unknown);

            // replace "$$_SysDateTime_$$" with the time set at the start of the program execution
            if (XID_unknown.equals("$$_SysDateTime_$$")) {
                knownFields.put(id_unknown, Test.getTime());
                itr.remove();
                continue;
            }

            // if the value of the XID was previously computed, remove it from the unknown fields
            // and move it to the known fields
            if (Test.XID_map.containsKey(XID_unknown)) {
                XID XID_object = Test.XID_map.get(XID_unknown);

                if (XID_object != null && XID_object.knownFields != null && XID_object.knownFields.containsKey(id_unknown)) {
                    knownFields.put(id_unknown, XID_object.knownFields.get(id_unknown));
                    itr.remove();
                }
            }
        }

        /* Add to the known fields the foreign keys with a value received previously and saved in Test.XID_map */
        for (Map.Entry<String, ForeignKey> foreignKeyEntry : foreignKeys.entrySet()) {
            String XID_foreignKey = foreignKeyEntry.getKey();
            ForeignKey foreignKey = foreignKeyEntry.getValue();

            if (Test.XID_map.containsKey(XID_foreignKey)) {
                XID XID_object = Test.XID_map.get(XID_foreignKey);
                if (XID_object != null && XID_object.knownFields != null && XID_object.knownFields.containsKey(foreignKey.referenced_column)) {
                    String prevFoundValue = XID_object.knownFields.get(foreignKey.referenced_column);
                    // save the value of foreign key in known fields
                    knownFields.put(foreignKey.referencing_column, prevFoundValue);
                }
            }
        }
    }

    /**
     * Construct the insert SQL query; then construct a select query to determine the ID of the newly inserted row
     * @return query for selecting the id of the inserted row
     * @throws Exception Validation error
     */
    private String constructInsertQuery() throws Exception {
        // if there are fields still unknown, return error
        if (!validateFieldsInsert(Test.XID_map)) {
            throw new Exception("Validation failed. Some information is not available.");
        }

        // all the unknown fields were found, append together all fields and values to form the insert query
        StringBuilder keys = new StringBuilder();
        StringBuilder fields = new StringBuilder();
        for (String s : knownFields.keySet()) {
            keys.append(s);
            keys.append(", ");
            // replace string "null" with actual null
            if (knownFields.get(s) == null) {
                fields.append(knownFields.get(s));
                fields.append(", ");
            } else {
                fields.append("'");
                fields.append(knownFields.get(s));
                fields.append("', ");
            }
        }
        // remove last ", "
        keys.delete(keys.length() - 2, keys.length());
        fields.delete(fields.length() - 2, fields.length());

        this.query = "INSERT INTO " + this.dataSet + " (" + keys + ") VALUES (" + fields + ");";

        /*
         * after the insert, we want to retrieve the id and all information inserted and put it back in the MAP
         * construct the SELECT query
         */

        // SELECT	-> the newly inserted PK
        String select = Test.XID_map.get(XID).dataSetKeys.get("PRIMARY KEY").get(0).columnName;
        // WHERE	-> this.knownFields
        String condition = setToStringWithDelimiter(getEquals(knownFields, " is "), "AND");

        // return as second sql query "SELECT id FROM ..."
        return "SELECT " + select + " FROM " + this.dataSet + " WHERE " + condition;
    }

    /**
     * Construct the select SQL query to be executed
     * @return second query to be executed
     * @throws Exception Invalid request: there must be at least a field unknown
     */
    private String constructSelectQuery() throws Exception {
        if (unknownFields.keySet().size() <= 0) {
            throw new Exception("Invalid request: there are no fields specified for the retrieve operation!");
        }

        // SELECT	-> unknownFields
        String select = setToStringWithDelimiter(unknownFields.keySet().toArray(), ",");

        // WHERE	-> this.knownFields
        String condition = setToStringWithDelimiter(getEquals(knownFields, " is "), "AND");
        // condition might be null -> there is no WHERE clause
        // TODO: is it possible not to have any KnownFields??
        String where = condition != null ? (" WHERE " + condition + ";") : "";

        // select the SQL query
        this.query =  "SELECT " + select + " FROM " + this.dataSet + where;

        // there is no secondary query to be executed after select
        return null;
    }

    /**
     * Construct the update SQL query to be executed
     * @return second query to be executed
     */
    private String constructUpdateQuery() {
        XID xid = Test.XID_map.get(XID);
        String pk = xid.dataSetKeys.get("PRIMARY KEY").get(0).columnName;
        // select the SQL query
        this.query =  "UPDATE " + this.dataSet +
                " SET " + setToStringWithDelimiter(getEquals(knownFields, "="), ",") +
                // TODO 0: check if update works with the new PK
                // TODO: id is hardcoded
                " WHERE " + pk + "='" + xid.knownFields.get(pk) + "'";

        // there is no secondary query to be executed after update: all information already in XID_map
        return null;
    }

    /**
     * Search if the unknown fields were assigned by a previous query
     * @param map hashmap to balidate
     * @return true if all unknownFields are found in the Test.XID_map; false if one unknown field was not found
     */
    private boolean validateFieldsInsert(HashMap<String, XID> map) {
        for (Map.Entry<String,String> entry : unknownFields.entrySet()) {
            String XID = entry.getValue();
            String id = entry.getKey();
            // skip the id field (it cannot exist in the database)
            String pk = Test.XID_map.get(XID).dataSetKeys.get("PRIMARY KEY").get(0).columnName;
            if (!id.equals(pk)) {
                // if the value is still unknown in the global map (still null)
                if (map.containsKey(XID)) {
                    if (map.get(XID) == null || map.get(XID).knownFields.get(id) == null) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Get a list of conditions from the given map fields;
     * use "=" as delimiter for normal values
     * use "is" for NULLs
     *
     * @return array of Objects (strings)
     */
    private Object[] getEquals(TreeMap<String, String> map, String nullDelimiter) {
        Object[] o = new Object[map.size()];

        /* nothing to work with */
        if (map.size() <= 0) {
            return null;
        }

        int i = 0;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            // if value is null, use "key is null" syntax
            if (entry.getValue() == null) {
                o[i++] = entry.getKey() + nullDelimiter + entry.getValue();
            } else {
                // else, use "key = value"
                o[i++] = entry.getKey() + "='" + entry.getValue() + "'";
            }
        }

        return o;
    }

    /**
     * Get a string containing the objects in the array, delimited by given delimiter
     *
     * @param arr array of Objects to be concatenated into a string
     * @param delimiter String to be placed between elements of array
     * @return string concatenation of all elements in array
     */
    private String setToStringWithDelimiter(Object[] arr, String delimiter) {
        StringBuilder sb = new StringBuilder();

        if (arr == null || arr.length <= 0) {
            return null;
        }

        for (Object o : arr) {
            sb.append(o);
            sb.append(" ");
            sb.append(delimiter);
            sb.append(" ");
        }

        // remove last delimiter and space
        sb.delete(sb.length() - 2 - delimiter.length(), sb.length());

        return sb.toString();
    }

    /**
     * Execute SQL query
     *
     * TODO: Assume there is only one record returned
     *
     * @return array containing the columns returned by the sql query
     */
    @org.springframework.transaction.annotation.Transactional
    private int executeQuery(Session session, String secondary_query_insert) {
        NativeQuery SQLQuery = session.createSQLQuery(query);
        int rows_affected = -1;

        /* only if the crud is "create", execute the secondary query */
        if (CRUD.equals("create")) {
            session.beginTransaction();
            rows_affected = SQLQuery.executeUpdate();
            session.getTransaction().commit();

            // do the secondary query: for insert/update, it should be "SELECT id FROM TABLE WHERE (...)"
            if (secondary_query_insert != null) {
                SQLQuery = session.createSQLQuery(secondary_query_insert);
            } else {
                return rows_affected;
            }
        }

        // get all the fields from the sql query
        SQLQuery.setResultTransformer(AliasToEntityMapResultTransformer.INSTANCE);

        // !! Returned fields can be Integers, so the value in the HashMap must be a parent of all types:
        // Strings, Integer, etc. => Object must be used
        List<HashMap<String, Object>> aliasToValueMapList = SQLQuery.list();

        System.err.println("=====SQL response:=========" + aliasToValueMapList + "================");

        // append to the known fields of the XID object all the rows returned by the SQL
        TreeMap<String, String> knownFields = new TreeMap<>();
        for (HashMap<String, Object> result : aliasToValueMapList) {
            for (String key : result.keySet()) {
                knownFields.put(key, result.get(key).toString());
            }
        }

        // return the number of rows returned by the sql query
        if (rows_affected == -1) {
            rows_affected = aliasToValueMapList.size();
        }

        // TODO: get all keys and insert them into the Test.XID_map

        // if unknownFields has no entries, it means there is no information required:
        // ex: insert with a known id
        if (unknownFields.size() > 0) {
            // TODO: hardcoded: only the first field is saved (the ID)
            String XID = unknownFields.firstEntry().getValue();

            // if the XID is already in the MAP,
            // update known fields in the map with ones returned as a result of running the current sql query
            if (Test.XID_map.containsKey(XID) && Test.XID_map.get(XID) != null) {
                // knownFields is null; init treeMap
                if (Test.XID_map.get(XID).knownFields == null) {
                    Test.XID_map.get(XID).knownFields = new TreeMap<>();
                }
                // put new fields in XID_map
                Test.XID_map.get(XID).knownFields.putAll(knownFields);
            } else {
                // XID is not present in the map; create and insert a new instance of XID object into the map
                Test.XID_map.put(XID, new XID(XID, this.dataSet, knownFields, getKeys(session, this.dataSet)));
            }
        }

        return rows_affected;
    }

    /**
     * Get SQL style date time
     * @param session Sql session
     * @return string containing current system date time
     * @throws Exception Cannot get time from SQL
     */
    static String getTime(Session session) throws Exception {
        NativeQuery SQLQuery = session.createSQLQuery("SELECT NOW();");
        List<Object> requestRoutines = SQLQuery.getResultList();

        if (requestRoutines.size() != 1)
            throw new Exception("Could not get current datetime");

        return requestRoutines.get(0).toString();
    }

    static HashMap<String, ArrayList<Key>> getKeys(Session session, String dataset) {
        HashMap<String, ArrayList<Key>> dataSetKeys = new HashMap<>();

        String getKeysQuery = "SELECT innerTable.constraint_type AS 'CONSTRAINT_TYPE', keyCol.`COLUMN_NAME` AS 'COLUMN_NAME', `keyCol`.`REFERENCED_TABLE_NAME` AS 'REFERENCED_TABLE_NAME', innerTable.constraint_name AS 'CONSTRAINT_NAME' " +
        "FROM (SELECT constr.constraint_type, constr.constraint_name, constr.table_name " +
                "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS constr " +
                "WHERE constr.table_name='"+dataset+"') innerTable " +
        "INNER JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE keyCol ON keyCol.table_name=innerTable.table_name AND keyCol.`CONSTRAINT_NAME`=innerTable.`CONSTRAINT_NAME` " +
        "ORDER BY constraint_type;";

        NativeQuery SQLQuery = session.createSQLQuery(getKeysQuery);

        // get all the fields from the sql query
        SQLQuery.setResultTransformer(AliasToEntityMapResultTransformer.INSTANCE);
        List<HashMap<String, String>> aliasToValueMapList = SQLQuery.list();

        System.err.println("=====SQL response:=========" + aliasToValueMapList + "================");

        for (HashMap<String, String> row : aliasToValueMapList) {
            String constraint_type = row.get("CONSTRAINT_TYPE");
            switch (constraint_type) {
                case "PRIMARY KEY":
                case "FOREIGN KEY":
                    if (dataSetKeys.get(constraint_type) == null) {
                        dataSetKeys.put(constraint_type, new ArrayList<>());
                    }
                    dataSetKeys.get(constraint_type).add(new Key(row.get("COLUMN_NAME"), row.get("REFERENCED_TABLE_NAME")));
                    break;
                case "UNIQUE":
                    System.out.println("Unique " + row.get("COLUMN_NAME"));
                    //TODO: add unique key to the map
                    break;
                default:
                    break;
            }
        }

        return dataSetKeys;
    }
}
