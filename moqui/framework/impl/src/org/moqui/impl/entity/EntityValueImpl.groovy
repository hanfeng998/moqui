/*
 * This Work is in the public domain and is provided on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * including, without limitation, any warranties or conditions of TITLE,
 * NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.
 * You are solely responsible for determining the appropriateness of using
 * this Work and assume any risks associated with your use of this Work.
 *
 * This Work includes contributions authored by David E. Jones, not as a
 * "work for hire", who hereby disclaims any copyright to the same.
 */
package org.moqui.impl.entity

import java.sql.Timestamp
import java.sql.Time
import java.sql.Date

import org.apache.commons.collections.set.ListOrderedSet
import org.moqui.entity.EntityValue
import org.moqui.entity.EntityList
import org.w3c.dom.Element
import org.w3c.dom.Document
import org.moqui.entity.EntityException
import org.moqui.entity.EntityCondition
import org.moqui.impl.entity.EntityQueryBuilder.EntityConditionParameter
import java.sql.ResultSet

class EntityValueImpl implements EntityValue {
    protected final static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(EntityDefinition.class)

    /** This is a reference to where the entity value came from.
     * It is volatile so not stored when this is serialized, and will get a reference to the active EntityFacade after. 
     */
    protected volatile EntityFacadeImpl efi

    protected final String entityName
    protected volatile EntityDefinition entityDefinition

    protected final Map<String, Object> valueMap = new HashMap()
    /* Original DB Value Map: not used unless the value has been modified from its original state from the DB */
    protected Map<String, Object> dbValueMap = null

    protected boolean modified = false
    protected boolean mutable = true

    protected EntityValueImpl(EntityDefinition entityDefinition, EntityFacadeImpl efi) {
        this.efi = efi
        this.entityName = entityDefinition.getEntityName()
        this.entityDefinition = entityDefinition
    }

    EntityFacadeImpl getEntityFacadeImpl() {
        // TODO: change this to handle null after deserialize
        return efi
    }
    EntityDefinition getEntityDefinition() {
        // TODO: change this to handle null after deserialize
        return entityDefinition
    }

    void setSyncedWithDb() { dbValueMap = (Map) valueMap.clone(); modified = false }

    /** @see org.moqui.entity.EntityValue#getEntityName() */
    String getEntityName() { return entityName }

    /** @see org.moqui.entity.EntityValue#isModified() */
    boolean isModified() { return modified }

    /** @see org.moqui.entity.EntityValue#isMutable() */
    boolean isMutable() { return mutable }

    /** @see org.moqui.entity.EntityValue#get(String) */
    Object get(String name) {
        if (!this.entityDefinition.isField(name)) {
            // if this is not a valid field name but is a valid relationship name, do a getRelated or getRelatedOne to return an EntityList or an EntityValue
            Node relationship = (Node) this.getEntityDefinition().entityNode.relationship.find({ it."@title" + it."@related-entity-name" == name })
            if (relationship) {
                if (relationship."@type" == "many") {
                    return this.findRelated(name, null, null, null)
                } else {
                    return this.findRelatedOne(name, null)
                }
            } else {
                throw new IllegalArgumentException("The name [${name}] is not a valid field name or relationship name for entity [${this.entityName}]")
            }
        }

        // TODO use LocalizedEntityField for any localized fields

        return this.valueMap[name]
    }

    /** @see org.moqui.entity.EntityValue#containsPrimaryKey() */
    boolean containsPrimaryKey() {
        for (String fieldName in this.getEntityDefinition().getFieldNames(true, false)) {
            if (!this.valueMap[fieldName]) return false
        }
        return true
    }

    /** @see org.moqui.entity.EntityValue#set(String, Object) */
    void set(String name, Object value) {
        if (!this.mutable) throw new IllegalArgumentException("Cannot set field [${name}], this entity value is not mutable (it is read-only)")
        if (!this.getEntityDefinition().isField(name)) {
            throw new IllegalArgumentException("The name [${name}] is not a valid field name for entity [${this.entityName}]")
        }
        this.modified = true
        this.valueMap.put(name, value)
    }

    /** @see org.moqui.entity.EntityValue#setString(String, String) */
    void setString(String name, String value) {
        if (value == null || value == "null") {
            set(name, null)
            return
        }
        if (value == "\null") value == "null"

        Node fieldNode = this.getEntityDefinition().getFieldNode(name)
        if (!fieldNode) set(name, value) // cause an error on purpose

        String javaType = this.getEntityFacadeImpl().getFieldJavaType(fieldNode."@type", entityName)
        switch (EntityFacadeImpl.getJavaTypeInt(javaType)) {
        case 1: set(name, value); break
        case 2: set(name, java.sql.Timestamp.valueOf(value)); break
        case 3: set(name, java.sql.Time.valueOf(value)); break
        case 4: set(name, java.sql.Date.valueOf(value)); break
        case 5: set(name, Integer.valueOf(value)); break
        case 6: set(name, Long.valueOf(value)); break
        case 7: set(name, Float.valueOf(value)); break
        case 8: set(name, Double.valueOf(value)); break
        case 9: set(name, new BigDecimal(value)); break
        case 10: set(name, Boolean.valueOf(value)); break
        case 11: set(name, value); break
        // better way for Blob (12)? probably not...
        case 12: set(name, value); break
        case 13: set(name, value); break
        case 14: set(name, value.asType(java.util.Date.class)); break
        // better way for Collection (15)? maybe parse comma separated, but probably doesn't make sense in the first place
        case 15: set(name, value); break
        }
    }

    /** @see org.moqui.entity.EntityValue#getBoolean(String) */
    Boolean getBoolean(String name) {
        return this.get(name) as Boolean
    }

    /** @see org.moqui.entity.EntityValue#getString(String) */
    String getString(String name) {
        Object valueObj = this.get(name)
        return valueObj ? valueObj.toString() : null
    }

    /** @see org.moqui.entity.EntityValue#getTimestamp(String) */
    Timestamp getTimestamp(String name) {
        // NOTE: all of these methods are using the Groovy asType to do type conversion
        // if any don't work use the org.apache.commons.beanutils.Converter stuff
        return (Timestamp) this.get(name).asType(Timestamp.class)
    }

    /** @see org.moqui.entity.EntityValue#getTime(String) */
    Time getTime(String name) {
        return this.get(name) as Time
    }

    /** @see org.moqui.entity.EntityValue#getDate(String) */
    Date getDate(String name) {
        return this.get(name) as Date
    }

    /** @see org.moqui.entity.EntityValue#getLong(String) */
    Long getLong(String name) {
        return this.get(name) as Long
    }

    /** @see org.moqui.entity.EntityValue#getDouble(String) */
    Double getDouble(String name) {
        return this.get(name) as Double
    }

    /** @see org.moqui.entity.EntityValue#getBigDecimal(String) */
    BigDecimal getBigDecimal(String name) {
        return this.get(name) as BigDecimal
    }

    /** @see org.moqui.entity.EntityValue#setFields(Map, boolean, java.lang.String, boolean) */
    void setFields(Map<String, ?> fields, boolean setIfEmpty, String namePrefix, Boolean pks) {
        if (fields == null) return

        Set fieldNameSet
        if (pks != null) {
            fieldNameSet = this.getEntityDefinition().getFieldNames(pks, !pks)
        } else {
            fieldNameSet = this.getEntityDefinition().getFieldNames(true, true)
        }

        for (String fieldName in fieldNameSet) {
            String sourceFieldName
            if (namePrefix) {
                sourceFieldName = namePrefix + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1)
            } else {
                sourceFieldName = fieldName
            }

            if (fields.containsKey(sourceFieldName)) {
                Object value = fields.get(sourceFieldName)

                if (value) {
                    if (value instanceof String) {
                        this.setString(fieldName, (String) value)
                    } else {
                        this.set(fieldName, value)
                    }
                } else if (setIfEmpty) {
                    // treat empty String as null, otherwise set as whatever null or empty type it is
                    if (value != null && value instanceof String) {
                        this.set(fieldName, null)
                    } else {
                        this.set(fieldName, value)
                    }
                }
            }
        }
    }

    /** @see org.moqui.entity.EntityValue#compareTo(EntityValue) */
    int compareTo(EntityValue that) {
        // nulls go earlier
        if (that == null) return -1

        // first entity names
        int result = this.entityName.compareTo(that.getEntityName())
        if (result != 0) return result

        // next compare PK fields
        for (String pkFieldName in this.getEntityDefinition().getFieldNames(true, false)) {
            result = compareFields(that, pkFieldName)
            if (result != 0) return result
        }
        // then non-PK fields
        for (String fieldName in this.getEntityDefinition().getFieldNames(false, true)) {
            result = compareFields(that, fieldName)
            if (result != 0) return result
        }

        // all the same, result should be 0
        return result
    }

    protected int compareFields(EntityValue that, String name) {
        Comparable thisVal = (Comparable) this.valueMap.get(name)
        Object thatVal = that.get(name)
        // NOTE: nulls go earlier in the list
        if (thisVal == null) {
            return thatVal == null ? 0 : 1
        } else {
            return thatVal == null ? -1 : thisVal.compareTo(thatVal)
        }
    }

    /** @see org.moqui.entity.EntityValue#create() */
    void create() {
        if (getEntityDefinition().isViewEntity()) {
            throw new IllegalArgumentException("Create not yet implemented for view-entity")
        } else {
            if (getEntityDefinition().isField("lastUpdatedStamp") && !this.get("lastUpdatedStamp"))
                this.set("lastUpdatedStamp", new Timestamp(System.currentTimeMillis()))

            ListOrderedSet allFieldList = getEntityDefinition().getFieldNames(true, true)
            ListOrderedSet fieldList = new ListOrderedSet()
            for (String fieldName in allFieldList) if (valueMap.containsKey(fieldName)) fieldList.add(fieldName)

            EntityQueryBuilder eqb = new EntityQueryBuilder(getEntityDefinition(), getEntityFacadeImpl())
            StringBuilder sql = eqb.getSqlTopLevel()
            sql.append("INSERT INTO ").append(getEntityDefinition().getTableName())

            sql.append(" (")
            boolean isFirstField = true
            StringBuilder values = new StringBuilder()
            for (String fieldName in fieldList) {
                if (isFirstField) {
                    isFirstField = false
                } else {
                    sql.append(", ")
                    values.append(", ")
                }
                sql.append(getEntityDefinition().getColumnName(fieldName, false))
                values.append('?')
            }
            sql.append(") VALUES (").append(values.toString()).append(')')

            try {
                eqb.makeConnection()
                eqb.makePreparedStatement()
                int index = 1
                for (String fieldName in fieldList) {
                    eqb.setPreparedStatementValue(index, valueMap.get(fieldName), getEntityDefinition().getFieldNode(fieldName))
                    index++
                }
                eqb.executeUpdate()
                setSyncedWithDb()
                // TODO: notify cache clear
            } catch (EntityException e) {
                throw new EntityException("Error in create of [${this.toString()}]", e)
            } finally {
                eqb.closeAll()
            }
        }
    }

    /** @see org.moqui.entity.EntityValue#createOrUpdate() */
    void createOrUpdate() {
        EntityValue dbValue = (EntityValue) this.clone()
        if (dbValue.refresh()) {
            create()
        } else {
            update()
        }
    }

    /** @see org.moqui.entity.EntityValue#update() */
    void update() {
        if (getEntityDefinition().isViewEntity()) {
            throw new IllegalArgumentException("Update not yet implemented for view-entity")
        } else {
            ListOrderedSet pkFieldList = getEntityDefinition().getFieldNames(true, false)

            ListOrderedSet nonPkAllFieldList = getEntityDefinition().getFieldNames(false, true)
            ListOrderedSet nonPkFieldList = new ListOrderedSet()
            for (String fieldName in nonPkAllFieldList)
                if (valueMap.containsKey(fieldName)) nonPkFieldList.add(fieldName)
            if (!nonPkFieldList) {
                logger.trace("Not doing update on entity with no populated non-PK fields; entity=" + this.toString())
                return
            }

            if (getEntityDefinition().getEntityNode()."@optimistic-lock" == "true") {
                EntityValue dbValue = (EntityValue) this.clone()
                dbValue.refresh()
                if (getTimestamp("lastUpdatedStamp") != dbValue.getTimestamp("lastUpdatedStamp"))
                    throw new EntityException("This record was updated by someone else at [${getTimestamp("lastUpdatedStamp")}] which was after the version you loaded at [${dbValue.getTimestamp("lastUpdatedStamp")}]. Not updating to avoid overwriting data.")
            }

            if (getEntityDefinition().isField("lastUpdatedStamp") && !this.get("lastUpdatedStamp"))
                this.set("lastUpdatedStamp", new Timestamp(System.currentTimeMillis()))

            EntityQueryBuilder eqb = new EntityQueryBuilder(getEntityDefinition(), getEntityFacadeImpl())
            StringBuilder sql = eqb.getSqlTopLevel()
            sql.append("UPDATE ").append(getEntityDefinition().getTableName()).append(" SET ")

            boolean isFirstField = true
            for (String fieldName in nonPkFieldList) {
                if (isFirstField) isFirstField = false else sql.append(", ")
                sql.append(getEntityDefinition().getColumnName(fieldName, false)).append("=?")
                eqb.getParameters().add(new EntityConditionParameter(getEntityDefinition().getFieldNode(fieldName),
                        valueMap.get(fieldName), eqb))
            }
            sql.append(" WHERE ")
            boolean isFirstPk = true
            for (String fieldName in pkFieldList) {
                if (isFirstPk) isFirstPk = false else sql.append(" AND ")
                sql.append(getEntityDefinition().getColumnName(fieldName, false)).append("=?")
                eqb.getParameters().add(new EntityConditionParameter(getEntityDefinition().getFieldNode(fieldName),
                        valueMap.get(fieldName), eqb))
            }

            try {
                eqb.makeConnection()
                eqb.makePreparedStatement()
                eqb.setPreparedStatementValues()
                if (eqb.executeUpdate() == 0)
                    throw new EntityException("Tried to update a value that does not exist [${this.toString()}].")
                setSyncedWithDb()
                // TODO: notify cache clear
            } catch (EntityException e) {
                throw new EntityException("Error in update of [${this.toString()}]", e)
            } finally {
                eqb.closeAll()
            }
        }
    }

    /** @see org.moqui.entity.EntityValue#delete() */
    void delete() {
        if (getEntityDefinition().isViewEntity()) {
            throw new IllegalArgumentException("Delete not implemented for view-entity")
        } else {
            ListOrderedSet pkFieldList = getEntityDefinition().getFieldNames(true, false)

            EntityQueryBuilder eqb = new EntityQueryBuilder(getEntityDefinition(), getEntityFacadeImpl())
            StringBuilder sql = eqb.getSqlTopLevel()
            sql.append("DELETE FROM ").append(getEntityDefinition().getTableName()).append(" WHERE ")

            boolean isFirstPk = true
            for (String fieldName in pkFieldList) {
                if (isFirstPk) isFirstPk = false else sql.append(" AND ")
                sql.append(getEntityDefinition().getColumnName(fieldName, false)).append("=?")
                eqb.getParameters().add(new EntityConditionParameter(getEntityDefinition().getFieldNode(fieldName),
                        valueMap.get(fieldName), eqb))
            }

            try {
                eqb.makeConnection()
                eqb.makePreparedStatement()
                eqb.setPreparedStatementValues()
                if (eqb.executeUpdate() == 0) logger.info("Tried to delete a value that does not exist [${this.toString()}]")
                // TODO: notify cache clear
            } catch (EntityException e) {
                throw new EntityException("Error in delete of [${this.toString()}]", e)
            } finally {
                eqb.closeAll()
            }
        }
    }

    /** @see org.moqui.entity.EntityValue#refresh() */
    boolean refresh() {
        // NOTE: this simple approach may not work for view-entities, but not restricting for now

        ListOrderedSet pkFieldList = getEntityDefinition().getFieldNames(true, false)
        ListOrderedSet nonPkFieldList = getEntityDefinition().getFieldNames(false, true)
        if (!nonPkFieldList) {
            logger.trace("Not doing refresh on entity with no non-PK fields; entity=" + this.toString())
            return
        }

        EntityQueryBuilder eqb = new EntityQueryBuilder(getEntityDefinition(), getEntityFacadeImpl())
        StringBuilder sql = eqb.getSqlTopLevel()
        sql.append("SELECT ")
        boolean isFirstField = true
        for (String fieldName in nonPkFieldList) {
            if (isFirstField) isFirstField = false else sql.append(", ")
            sql.append(getEntityDefinition().getColumnName(fieldName, false))
        }

        sql.append(" FROM ").append(getEntityDefinition().getTableName()).append(" WHERE ")

        boolean isFirstPk = true
        for (String fieldName in pkFieldList) {
            if (isFirstPk) isFirstPk = false else sql.append(" AND ")
            sql.append(getEntityDefinition().getColumnName(fieldName, false)).append("=?")
            eqb.getParameters().add(new EntityConditionParameter(getEntityDefinition().getFieldNode(fieldName),
                    valueMap.get(fieldName), eqb))
        }

        boolean retVal = false
        try {
            eqb.makeConnection()
            eqb.makePreparedStatement()
            eqb.setPreparedStatementValues()

            ResultSet rs = eqb.executeQuery()
            if (rs.next()) {
                int j = 1
                for (String fieldName in nonPkFieldList) {
                    EntityQueryBuilder.getResultSetValue(rs, j, getEntityDefinition().getFieldNode(fieldName), this, getEntityFacadeImpl())
                    j++
                }
                retVal = true
            } else {
                logger.trace("No record found in refresh for entity [${entityName}] with values [${valueMap}]")
            }
        } catch (EntityException e) {
            throw new EntityException("Error in refresh of [${this.toString()}]", e)
        } finally {
            eqb.closeAll()
        }
        return retVal
    }

    /** @see org.moqui.entity.EntityValue#getOriginalDbValue(String) */
    Object getOriginalDbValue(String name) {
        return (dbValueMap && dbValueMap[name]) ? dbValueMap[name] : valueMap[name]
    }

    /** @see org.moqui.entity.EntityValue#findRelated(String, Map, java.util.List<java.lang.String>, boolean) */
    EntityList findRelated(String relationshipName, Map<String, ?> byAndFields, List<String> orderBy, Boolean useCache) {
        // TODO implement this
        return null;
    }

    /** @see org.moqui.entity.EntityValue#findRelatedOne(String, boolean) */
    EntityValue findRelatedOne(String relationshipName, Boolean useCache) {
        // TODO implement this
        return null;
    }

    /** @see org.moqui.entity.EntityValue#deleteRelated(String) */
    void deleteRelated(String relationshipName) {
        // TODO implement this

    }

    /** @see org.moqui.entity.EntityValue#checkFks(boolean) */
    boolean checkFks(boolean insertDummy) {
        // TODO implement this
        return false
    }

    /** @see org.moqui.entity.EntityValue#makeXmlElement(Document, String) */
    Element makeXmlElement(Document document, String prefix) {
        // TODO implement this
        return null
    }

    /** @see org.moqui.entity.EntityValue#writeXmlText(PrintWriter, String) */
    void writeXmlText(PrintWriter writer, String prefix) {
        // TODO implement this
    }

    // ========== Map Interface Methods ==========

    /** @see java.util.Map#size() */
    int size() { return valueMap.size() }

    boolean isEmpty() { return valueMap.isEmpty() }

    boolean containsKey(Object o) { return valueMap.containsKey(o) }

    boolean containsValue(Object o) { return valueMap.containsValue(o) }

    Object get(Object o) {
        if (o instanceof String) {
            // This may throw an exception, and let it; the Map interface doesn't provide for IllegalArgumentException
            //   but it is far more useful than a log message that is likely to be ignored.
            return this.get((String) o)
        } else {
            return null
        }
    }

    Object put(String k, Object v) {
        Object original = this.get(k)
        this.set(k, v)
        return original
    }

    Object remove(Object o) {
        modified = true
        return valueMap.remove(o)
    }

    void putAll(Map<? extends String, ? extends Object> map) {
        for(Map.Entry entry in map.entrySet()) {
            this.set((String) entry.key, entry.value)
        }
    }

    void clear() { valueMap.clear() }

    Set<String> keySet() { return Collections.unmodifiableSet(valueMap.keySet()) }

    Collection<Object> values() { return Collections.unmodifiableCollection(valueMap.values()) }

    Set<Map.Entry<String, Object>> entrySet() { return Collections.unmodifiableSet(valueMap.entrySet()) }

    // ========== Object Override Methods ==========

    @Override
    public boolean equals(Object obj) {
        if (obj == null || obj.getClass() != this.getClass()) return false
        // reuse the compare method
        return this.compareTo((EntityValue) obj) == 0
    }

    @Override
    public int hashCode() {
        // NOTE: consider caching the hash code in the future for performance
        // divide both by two (shift to right one bit) to maintain scale and add together
        return this.entityName.hashCode() >> 1 + this.valueMap.hashCode() >> 1
    }

    @Override
    String toString() {
        // general SQL where clause style text with values included
        return "[${entityName}: ${valueMap}]"
    }

    @Override
    public Object clone() {
        return this.cloneValue()
    }

    public EntityValue cloneValue() {
        EntityValueImpl newObj = new EntityValueImpl(getEntityDefinition(), entityFacadeImpl)
        newObj.valueMap.putAll(valueMap)
        if (dbValueMap) newObj.dbValueMap = dbValueMap.clone()
        // don't set mutable (default to mutable even if original was not) or modified (start out not modified)
        return newObj
    }
}
