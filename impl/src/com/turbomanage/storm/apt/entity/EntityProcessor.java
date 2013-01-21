/*******************************************************************************
 * Copyright 2012 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.turbomanage.storm.apt.entity;

import java.util.List;
import java.util.Set;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

import com.turbomanage.storm.SQLiteDao;
import com.turbomanage.storm.api.Entity;
import com.turbomanage.storm.api.Id;
import com.turbomanage.storm.apt.ClassProcessor;
import com.turbomanage.storm.apt.StormEnvironment;
import com.turbomanage.storm.apt.converter.ConverterModel;
import com.turbomanage.storm.apt.database.DatabaseModel;
import com.turbomanage.storm.exception.TypeNotSupportedException;

public class EntityProcessor extends ClassProcessor {

	private static final String ID_FIELD = "id";
	private static final String TAG = EntityProcessor.class.getName();
	private EntityModel entityModel;

	public EntityProcessor(Element el, StormEnvironment stormEnv) {
		super(el, stormEnv);
	}

	@Override
	public EntityModel getModel() {
		return this.entityModel;
	}

	@Override
	public void populateModel() {
		Entity entity = this.typeElement.getAnnotation(Entity.class);
		this.entityModel = new EntityModel();
		super.populateModel();
		this.entityModel.addImport(getQualifiedClassName());
		parseEntity(entity);
		chooseBaseDao(entity);
		chooseDatabase(entity);
		readFields(typeElement);
		inspectId();
	}

	private void parseEntity(Entity entity) {
		String tableName = entity.tableName();
		if (tableName != null && tableName.length() > 0) {
			this.entityModel.setTableName(tableName);
		} else {
			this.entityModel.setTableName(getClassName());
		}
	}

	protected void chooseBaseDao(Entity entity) {
//		List<String> iNames = super.inspectInterfaces();
//		if (iNames.contains(Persistable.class.getName())) {
		this.entityModel.setBaseDaoClass(SQLiteDao.class);
	}

	protected void chooseDatabase(Entity entity) {
		DatabaseModel defaultDb = stormEnv.getDefaultDb();
		if (entity.dbName().length() > 0) {
			// Add db to entity model and vice versa
			String dbName = entity.dbName();
			DatabaseModel db = stormEnv.getDbByName(dbName);
			if (db != null) {
				this.entityModel.setDatabase(db);
			} else {
				stormEnv.getLogger().error(TAG + ": There is no @Database named " + dbName, this.typeElement);
			}
		} else if (defaultDb != null) {
			this.entityModel.setDatabase(defaultDb);
		} else {
			stormEnv.getLogger().error(TAG + ": You must define at least one @Database", this.typeElement);
		}
	}

	@Override
	protected void inspectField(VariableElement field) {
		Set<Modifier> modifiers = field.getModifiers();
		boolean hasId = (field.getAnnotation(Id.class) != null);
		if (!modifiers.contains(Modifier.TRANSIENT)) {
			String javaType = getFieldType(field);
			if (TypeKind.DECLARED.equals(field.asType().getKind())) {
				DeclaredType type = (DeclaredType) field.asType();
				TypeElement typeElement = (TypeElement) type.asElement();
				TypeMirror superclass = typeElement.getSuperclass();
				if (ElementKind.ENUM.equals(typeElement.getKind())) {
					if (hasId) {
						stormEnv.getLogger().error("@Id invalid on enums", field);
					} else {
						FieldModel fm = new FieldModel(field.getSimpleName().toString(), javaType, true, stormEnv.getConverterForType("java.lang.Enum"));
						entityModel.addField(fm);
					}
					return;
				}
			}
			// Verify supported type
			try {
				ConverterModel converter = stormEnv.getConverterForType(javaType);
				FieldModel f = new FieldModel(field.getSimpleName().toString(), javaType, false, converter);
				if (hasId) {
					if (entityModel.getIdField() == null) {
						if ("long".equals(f.getJavaType())) {
							entityModel.setIdField(f);
						} else {
							stormEnv.getLogger().error("@Id field must be of type long", field);
						}
					} else {
						stormEnv.getLogger().error("Duplicate @Id", field);
					}
				}
				entityModel.addField(f);
			} catch (TypeNotSupportedException e) {
				stormEnv.getLogger().error(TAG + "inspectField", e, field);
			} catch (Exception e) {
				stormEnv.getLogger().error(TAG, e, field);
			}
			// TODO verify getter + setter
		} else if (hasId) {
			stormEnv.getLogger().error("@Id fields cannot be transient", field);
		}
	}

	/**
	 * Verifies that the entity has exactly one id field of type long. 
	 */
	private void inspectId() {
		if (entityModel.getIdField() == null) {
			// Default to field named "id"
			List<FieldModel> fields = entityModel.getFields();
			for (FieldModel f : fields) {
				if (ID_FIELD.equals(f.getFieldName())) {
					entityModel.setIdField(f);
				}
			}
		}
		FieldModel idField = entityModel.getIdField();
		if (idField != null && "long".equals(idField.getJavaType())) {
			return;
		} else {
			stormEnv.getLogger().error("Entity must contain a field named id or annotated with @Id of type long", typeElement);
		}
	}

}