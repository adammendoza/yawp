package endpoint;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.google.appengine.api.datastore.Cursor;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityNotFoundException;
import com.google.appengine.api.datastore.FetchOptions;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.PreparedQuery;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.Query.CompositeFilterOperator;
import com.google.appengine.api.datastore.Query.Filter;
import com.google.appengine.api.datastore.Query.FilterOperator;
import com.google.appengine.api.datastore.Query.FilterPredicate;
import com.google.appengine.api.datastore.QueryResultList;

import endpoint.utils.EntityUtils;

public class DatastoreQuery<T> {

	private Class<T> clazz;

	private Repository r;

	private Key parentKey;

	private Object[] where;

	private List<DatastoreQueryOrder> preOrders = new ArrayList<DatastoreQueryOrder>();

	private List<DatastoreQueryOrder> postOrders = new ArrayList<DatastoreQueryOrder>();

	private Integer limit;

	private String cursor;

	public static <T> DatastoreQuery<T> q(Class<T> clazz, Repository r) {
		return new DatastoreQuery<T>(clazz, r);
	}

	protected DatastoreQuery() {
	}

	private DatastoreQuery(Class<T> clazz, Repository r) {
		this.clazz = clazz;
		this.r = r;
	}

	public DatastoreQueryTransformer<?> transform(String transformName) {
		return new DatastoreQueryTransformer<Object>(this, Object.class, transformName);
	}

	public <TT> DatastoreQueryTransformer<TT> transform(Class<TT> transformClazz, String transformName) {
		return new DatastoreQueryTransformer<TT>(this, transformClazz, transformName);
	}

	public DatastoreQuery<T> where(Object... values) {
		this.where = values;
		return this;
	}

	public DatastoreQuery<T> parent(Key parentKey) {
		this.parentKey = parentKey;
		return this;
	}

	public DatastoreQuery<T> order(String property, String direction) {
		preOrders.add(new DatastoreQueryOrder(property, direction));
		return this;
	}

	public DatastoreQuery<T> sort(String property, String direction) {
		postOrders.add(new DatastoreQueryOrder(property, direction));
		return this;
	}

	public DatastoreQuery<T> limit(int limit) {
		this.limit = limit;
		return this;
	}

	public DatastoreQuery<T> cursor(String cursor) {
		this.cursor = cursor;
		return this;
	}

	public String getCursor() {
		return this.cursor;
	}

	public Repository getRepository() {
		return this.r;
	}

	public DatastoreQuery<T> options(DatastoreQueryOptions options) {
		if (options.getWhere() != null) {
			where(options.getWhere());
		}

		if (!options.getOrders().isEmpty()) {
			preOrders.addAll(options.getOrders());
		}

		if (options.getLimit() != null) {
			limit(options.getLimit());
		}

		return this;
	}

	public List<T> list() {
		r.namespace().set(getClazz());
		try {
			return executeQuery();
		} finally {
			r.namespace().reset();
		}
	}

	public T first() {
		r.namespace().set(getClazz());
		try {
			limit(1);

			List<T> list = executeQuery();
			if (list.size() == 0) {
				return null;
			}
			return list.get(0);
		} finally {
			r.namespace().reset();
		}
	}

	public T id(Long id) {
		r.namespace().set(getClazz());
		try {
			DatastoreService datastoreService = DatastoreServiceFactory.getDatastoreService();

			try {
				Entity entity = datastoreService.get(EntityUtils.createKey(id, clazz));
				T object = EntityUtils.toObject(entity, clazz);
				loadLists(object);
				return object;
			} catch (EntityNotFoundException e) {
				return null;
			}
		} finally {
			r.namespace().reset();
		}
	}

	private void loadLists(Object object) {
		Field[] fields = EntityUtils.getFields(object.getClass());
		for (int i = 0; i < fields.length; i++) {
			Field field = fields[i];
			if (!EntityUtils.isSaveAsList(field)) {
				continue;
			}

			field.setAccessible(true);

			List<Object> list = new ArrayList<Object>();
			list.addAll(q(EntityUtils.getListClass(field), r).parent(EntityUtils.getKey(object)).list());

			try {
				field.set(object, list);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}

	private List<T> executeQuery() {
		PreparedQuery pq = prepareQuery();
		FetchOptions fetchOptions = configureFetchOptions();

		QueryResultList<Entity> queryResult = pq.asQueryResultList(fetchOptions);

		List<T> objects = new ArrayList<T>();

		for (Entity entity : queryResult) {
			T object = EntityUtils.toObject(entity, clazz);
			objects.add(object);
		}

		this.cursor = queryResult.getCursor().toWebSafeString();
		return doPostOrder(objects);
	}

	private List<T> doPostOrder(List<T> objects) {
		Collections.sort(objects, new Comparator<T>() {
			@SuppressWarnings("rawtypes")
			@Override
			public int compare(T o1, T o2) {
				for (DatastoreQueryOrder order : postOrders) {
					Comparable value1 = (Comparable) EntityUtils.getter(o1, order.getProperty());
					Comparable value2 = (Comparable) EntityUtils.getter(o2, order.getProperty());

					@SuppressWarnings("unchecked")
					int compare = value1.compareTo(value2);
					if (compare == 0) {
						continue;
					}
					if (order.isDesc()) {
						return compare * -1;
					}
					return compare;
				}
				return 0;
			}
		});
		return objects;
	}

	private FetchOptions configureFetchOptions() {
		FetchOptions fetchOptions = FetchOptions.Builder.withDefaults();

		if (limit != null) {
			fetchOptions.limit(limit);
		}
		if (cursor != null) {
			fetchOptions.startCursor(Cursor.fromWebSafeString(cursor));
		}
		return fetchOptions;
	}

	private PreparedQuery prepareQuery() {
		Query q = new Query(EntityUtils.getKind(clazz));

		prepareQueryAncestor(q);
		prepareQueryWhere(q);
		prepareQueryOrder(q);

		DatastoreService service = DatastoreServiceFactory.getDatastoreService();
		return service.prepare(q);
	}

	private void prepareQueryOrder(Query q) {
		if (preOrders.isEmpty()) {
			return;
		}

		for (DatastoreQueryOrder order : preOrders) {
			String string = EntityUtils.getIndexFieldName(order.getProperty(), clazz);
			q.addSort(string, order.getSortDirection());
		}
	}

	private void prepareQueryWhere(Query q) {
		if (where == null) {
			return;
		}
		List<Filter> filters = new ArrayList<Filter>();

		int i = 0;
		while (i < where.length) {
			String fieldName = (String) where[i + 0];
			String indexFieldName = EntityUtils.getIndexFieldName(fieldName, clazz);
			Object value = EntityUtils.getIndexFieldValue(fieldName, clazz, where[i + 2]);
			filters.add(new FilterPredicate(indexFieldName, getFilterOperator(where[i + 1]), value));
			i += 3;
		}

		if (filters.size() > 1) {
			q.setFilter(CompositeFilterOperator.and(filters));
		} else {
			q.setFilter(filters.get(0));
		}
	}

	private void prepareQueryAncestor(Query q) {
		if (parentKey == null) {
			return;
		}
		q.setAncestor(parentKey);
	}

	protected Class<T> getClazz() {
		return clazz;
	}

	private FilterOperator getFilterOperator(Object o) {
		String operator = (String) o;

		if (operator.equals("=")) {
			return FilterOperator.EQUAL;
		}
		if (operator.equals(">")) {
			return FilterOperator.GREATER_THAN;
		}
		if (operator.equals(">=")) {
			return FilterOperator.GREATER_THAN_OR_EQUAL;
		}
		if (operator.equalsIgnoreCase("in")) {
			return FilterOperator.IN;
		}
		if (operator.equals("<")) {
			return FilterOperator.LESS_THAN;
		}
		if (operator.equals("<=")) {
			return FilterOperator.LESS_THAN_OR_EQUAL;
		}
		if (operator.equals("!=")) {
			return FilterOperator.NOT_EQUAL;
		}
		throw new RuntimeException("invalid filter operator");
	}

}
