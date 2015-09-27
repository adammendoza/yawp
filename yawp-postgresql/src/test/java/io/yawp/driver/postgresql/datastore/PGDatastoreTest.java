package io.yawp.driver.postgresql.datastore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import io.yawp.driver.postgresql.connection.ConnectionManager;
import io.yawp.driver.postgresql.connection.SqlRunner;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class PGDatastoreTest extends PGDatastoreTestCase {

	private PGDatastore datastore;

	@Before
	public void before() {
		datastore = PGDatastore.create(new ConnectionManager());
		truncate();
	}

	private void truncate() {
		new SqlRunner("truncate table people;").execute(connection);
	}

	@Test
	@Ignore
	public void testPopulate() {
		for (int i = 0; i < 1000; i++) {
			Entity entity = new Entity("people");
			entity.setProperty("name", NameGenerator.generate());
			datastore.put(entity);
		}
	}

	@Test
	public void testCreateRetrieveEntity() {
		Entity entity = new Entity("people");
		entity.setProperty("name", "jim");

		datastore.put(entity);

		Entity retrievedEntity = datastore.get(entity.getKey());
		assertEquals("jim", retrievedEntity.getProperty("name"));
	}

	@Test
	public void testCreateUpdateEntity() {
		Entity entity = new Entity("people");
		entity.setProperty("name", "jim");

		Key key = datastore.put(entity);

		entity.setProperty("name", "robert");
		datastore.put(entity);

		Entity retrievedEntity = datastore.get(key);
		assertEquals("robert", retrievedEntity.getProperty("name"));

	}

	@Test
	public void delete() {
		Key key = KeyFactory.createKey("people", "xpto");
		Entity entity = new Entity(key);
		datastore.put(entity);

		datastore.delete(key);

		assertNull(datastore.get(key));
	}

	@Test
	public void testForceName() {
		Key key = KeyFactory.createKey("people", "xpto");

		Entity entity = new Entity(key);
		entity.setProperty("name", "jim");

		datastore.put(entity);

		Entity retrievedEntity = datastore.get(key);
		assertEquals("jim", retrievedEntity.getProperty("name"));
	}

	@Test
	public void testForceId() {
		Key key = KeyFactory.createKey("people", 123l);

		Entity entity = new Entity(key);
		entity.setProperty("name", "jim");

		datastore.put(entity);

		Entity retrievedEntity = datastore.get(key);
		assertEquals("jim", retrievedEntity.getProperty("name"));
	}

	@Test
	public void testChildKey() {
		Key parentKey = KeyFactory.createKey("parents", 1l);
		Key childKey = KeyFactory.createKey(parentKey, "people", 1l);

		Entity entity = new Entity(childKey);
		entity.setProperty("name", "jim");

		datastore.put(entity);

		Entity retrievedEntity = datastore.get(childKey);
		assertEquals("jim", retrievedEntity.getProperty("name"));

		Key anotherParentKey = KeyFactory.createKey("parents", 2l);
		Key anotherChildKey = KeyFactory.createKey(anotherParentKey, "people", 1l);
		assertNull(datastore.get(anotherChildKey));
	}

	@Test
	public void testGrandchildKey() {
		Key parentKey = KeyFactory.createKey("parents", 1l);
		Key childKey = KeyFactory.createKey(parentKey, "children", 1l);
		Key grandchildKey = KeyFactory.createKey(childKey, "people", 1l);

		Entity entity = new Entity(grandchildKey);
		entity.setProperty("name", "jim");

		datastore.put(entity);

		Entity retrievedEntity = datastore.get(grandchildKey);
		assertEquals("jim", retrievedEntity.getProperty("name"));

		Key anotherParentKey = KeyFactory.createKey("parents", 2l);
		Key anotherChildKey = KeyFactory.createKey(anotherParentKey, "children", 1l);
		Key anotherGrandchildKey = KeyFactory.createKey(anotherChildKey, "people", 1l);

		assertNull(datastore.get(anotherGrandchildKey));
	}

}
