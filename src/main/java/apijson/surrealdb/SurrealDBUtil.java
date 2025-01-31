/*Copyright ©2024 APIJSON(https://github.com/APIJSON)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.*/

package apijson.surrealdb;

import apijson.*;
import apijson.orm.AbstractParser;
import apijson.orm.SQLConfig;
import com.alibaba.fastjson.JSONObject;
import com.surrealdb.*;
import com.surrealdb.signin.Root;
import com.surrealdb.signin.Signin;

import java.lang.Object;
import java.sql.SQLException;
import java.util.*;

import static apijson.orm.AbstractSQLExecutor.KEY_RAW_LIST;


/**
 * @author Lemon
 * @see DemoSQLExecutor 重写 execute 方法：
 *     \@Override
 *      public JSONObject execute(@NotNull SQLConfig<Long> config, boolean unknownType) throws Exception {
 *          if (config.isSurrealDB()) {
 *              return SurrealDBUtil.execute(config, null, unknownType);
 *          }
 *
 *          return super.execute(config, unknownType);
 *     }
 *
 *     DemoSQLConfig 重写方法 getNamespace, getSQLNamespace, getSchema, getSQLSchema 方法
 *    \@Override
 * 	   public String getNamespace() {
 * 		   return SurrealDBUtil.getNamespace(super.getNamespace(), DEFAULT_NAMESPACE, isSurrealDB(    ;
 * 	   }
 * 	  \@Override
 * 	   public String getSQLNamespace() {
 * 		   return SurrealDBUtil.getSQLNamespace(super.getSQLNamespace(), isSurrealDB(    ;
 * 	   }
 *
 *    \@Override
 *     public String getSchema() {
 * 	       return SurrealDBUtil.getSchema(super.getSchema(), DEFAULT_SCHEMA, isSurreal());
 *     }
 *
 *    \@Override
 *     public String getSQLSchema() {
 * 		   return SurrealDBUtil.getSQLSchema(super.getSQLSchema(), isSurrealDB());
 *     }
 */
public class SurrealDBUtil {
    public static final String TAG = "SurrealDBUtil";

    public static String getNamespace(String namespace, String defaultNamespace) {
        return getNamespace(namespace, defaultNamespace, true);
    }
    public static String getNamespace(String namespace, String defaultNamespace, boolean isSurrealDB) {
        if (isSurrealDB && StringUtil.isEmpty(namespace)) {
            namespace = defaultNamespace;
        }
        return namespace;
    }

    public static String getSQLNamespace(String namespace) {
        return getSQLNamespace(namespace, true);
    }
    public static String getSQLNamespace(String namespace, boolean isSurrealDB) {
        return isSurrealDB ? null : namespace;
    }

    public static String getSchema(String schema, String defaultSchema) {
        return getSchema(schema, defaultSchema, true);
    }
    public static String getSchema(String schema, String defaultSchema, boolean isSurrealDB) {
        if (isSurrealDB && StringUtil.isEmpty(schema)) {
            schema = defaultSchema;
        }
        return schema;
    }

    public static String getSQLSchema(String schema) {
        return getSQLSchema(schema, true);
    }
    public static String getSQLSchema(String schema, boolean isSurrealDB) {
        return isSurrealDB ? null : schema;
    }

    public static <T> String getClientKey(@NotNull SQLConfig<T> config) {
        String uri = config.getDBUri();
        return uri + (uri.contains("?") ? "&" : "?") + "username=" + config.getDBAccount();
    }

    public static final Map<String, Surreal> CLIENT_MAP = new LinkedHashMap<>();
    public static <T> Surreal getClient(@NotNull SQLConfig<T> config) {
        return getClient(config, true);
    }
    public static <T> Surreal getClient(@NotNull SQLConfig<T> config, boolean autoNew) {
        String key = getClientKey(config);

        Surreal client = CLIENT_MAP.get(key);
        if (autoNew && client == null) {
            client = new Surreal();
            client.connect(config.getDBUri());
            Signin signin = new Root(config.getDBAccount(), config.getDBPassword());
            client.signin(signin);
            client.useNs(config.getNamespace());
            client.useDb(config.getSchema());

            CLIENT_MAP.put(key, client);
        }

        return client;
    }

    public static <T> void closeClient(@NotNull SQLConfig<T> config) {
        Surreal client = getClient(config, false);
        if (client != null) {
            String key = getClientKey(config);
            CLIENT_MAP.remove(key);

//            try {
            client.close();
//            }
//            catch (Throwable e) {
//                e.printStackTrace();
//            }
        }
    }

    public static <T> void closeAllClient() {
        Collection<Surreal> cs = CLIENT_MAP.values();
        for (Surreal c : cs) {
            try {
                c.close();
            }
            catch (Throwable e) {
                e.printStackTrace();
            }
        }

        CLIENT_MAP.clear();
    }


    public static <T> JSONObject execute(@NotNull SQLConfig<T> config, String sql, boolean unknownType) throws Exception {
        if (RequestMethod.isQueryMethod(config.getMethod())) {
            List<JSONObject> list = executeQuery(config, sql, unknownType);
            JSONObject result = list == null || list.isEmpty() ? null : list.get(0);
            if (result == null) {
                result = new JSONObject(true);
            }

            if (list != null && list.size() > 1) {
                result.put(KEY_RAW_LIST, list);
            }

            return result;
        }

        return executeUpdate(config, sql);
    }

    public static <T> int execUpdate(SQLConfig<T> config, String sql) throws Exception {
        JSONObject result = executeUpdate(config, sql);
        return result.getIntValue(JSONResponse.KEY_COUNT);
    }

    public static <T> JSONObject executeUpdate(@NotNull SQLConfig<T> config, String sql) throws Exception {
        return executeUpdate(null, config, sql);
    }
    public static <T> JSONObject executeUpdate(Surreal client, @NotNull SQLConfig<T> config, String sql) throws Exception {
        if (client == null) {
            client = getClient(config);
        }

        List<JSONObject> list = executeQuery(config, sql, false);
        // 返回的是当前插入的表记录  JSONObject first = list == null || list.isEmpty() ? null : list.get(0);

        JSONObject result = AbstractParser.newSuccessResult();

        RequestMethod method = config.getMethod();
        if (method == RequestMethod.POST) {
            List<List<Object>> values = config.getValues();
            result.put(JSONResponse.KEY_COUNT, values == null ? 0 : values.size());
        } else {
            String idKey = config.getIdKey();
            Object id = config.getId();
            Object idIn = config.getIdIn();
            if (id != null) {
                result.put(idKey, id);
            }
            if (idIn != null) {
                result.put(idKey + "[]", idIn);
            }

            // FIXME 直接 SQLAuto 传 Flux/InfluxQL INSERT 如何取数量？
            result.put(JSONResponse.KEY_COUNT, id == null && idIn instanceof Collection ? ((Collection<?>) idIn).size() : 1);
        }

        return result;
    }


    public static JSONObject execQuery(@NotNull SQLConfig<?> config, String sql, boolean unknownType) throws Exception {
        List<JSONObject> list = executeQuery(config, sql, unknownType);
        JSONObject result = list == null || list.isEmpty() ? null : list.get(0);
        if (result == null) {
            result = new JSONObject(true);
        }

        if (list != null && list.size() > 1) {
            result.put(KEY_RAW_LIST, list);
        }

        return result;
    }

    public static List<JSONObject> executeQuery(@NotNull SQLConfig<?> config, String sql, boolean unknownType) throws Exception {
        return executeQuery(null, config, sql, unknownType);
    }
    public static List<JSONObject> executeQuery(Surreal client, @NotNull SQLConfig<?> config, String sql, boolean unknownType) throws Exception {
        if (client == null) {
            client = getClient(config);
        }

        Response response = client.query(sql);
        Array arr = response.take(0).getArray();

        List<JSONObject> resultList = new ArrayList<>();

        for (int i = 0; i < arr.len(); i++) {
            Value val = arr.get(i);
            JSONObject obj = new JSONObject(true);

            //TODO 递归处理所有类型
            if (val.isObject()) {
                com.surrealdb.Object o = val.getObject();
                for (Entry ety : o) {
                    Value v = ety.getValue();
//                    obj.put(ety.getKey(), JSON.parse(v.toString()));
                    obj.put(ety.getKey(), v.isNull() ? null : JSON.parse(v.toString()));
                }
            } else if (val.isArray()) {
                Array arr2 = val.getArray();
                for (int j = 0; j < arr2.len(); j++) {
                    Value v = arr2.get(j);
                }
            } else {
                obj.put("value", val.isNull() ? null : val.toString());
            }

            resultList.add(obj);
        }

        return resultList;
    }


}
