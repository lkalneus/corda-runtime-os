/**
 * Autogenerated by Avro
 *
 * DO NOT EDIT DIRECTLY
 */
package net.corda.data.config;

import org.apache.avro.generic.GenericArray;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.util.Utf8;
import org.apache.avro.message.BinaryMessageEncoder;
import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.SchemaStore;

@org.apache.avro.specific.AvroGenerated
public class Configuration extends org.apache.avro.specific.SpecificRecordBase implements org.apache.avro.specific.SpecificRecord {
  private static final long serialVersionUID = -7606360065481172322L;
  public static final org.apache.avro.Schema SCHEMA$ = new org.apache.avro.Schema.Parser().parse("{\"type\":\"record\",\"name\":\"Configuration\",\"namespace\":\"net.corda.data.config\",\"fields\":[{\"name\":\"value\",\"type\":{\"type\":\"string\",\"avro.java.string\":\"String\"}},{\"name\":\"version\",\"type\":{\"type\":\"string\",\"avro.java.string\":\"String\"}}]}");
  public static org.apache.avro.Schema getClassSchema() { return SCHEMA$; }

  private static SpecificData MODEL$ = new SpecificData();

  private static final BinaryMessageEncoder<Configuration> ENCODER =
      new BinaryMessageEncoder<Configuration>(MODEL$, SCHEMA$);

  private static final BinaryMessageDecoder<Configuration> DECODER =
      new BinaryMessageDecoder<Configuration>(MODEL$, SCHEMA$);

  /**
   * Return the BinaryMessageEncoder instance used by this class.
   * @return the message encoder used by this class
   */
  public static BinaryMessageEncoder<Configuration> getEncoder() {
    return ENCODER;
  }

  /**
   * Return the BinaryMessageDecoder instance used by this class.
   * @return the message decoder used by this class
   */
  public static BinaryMessageDecoder<Configuration> getDecoder() {
    return DECODER;
  }

  /**
   * Create a new BinaryMessageDecoder instance for this class that uses the specified {@link SchemaStore}.
   * @param resolver a {@link SchemaStore} used to find schemas by fingerprint
   * @return a BinaryMessageDecoder instance for this class backed by the given SchemaStore
   */
  public static BinaryMessageDecoder<Configuration> createDecoder(SchemaStore resolver) {
    return new BinaryMessageDecoder<Configuration>(MODEL$, SCHEMA$, resolver);
  }

  /**
   * Serializes this Configuration to a ByteBuffer.
   * @return a buffer holding the serialized data for this instance
   * @throws java.io.IOException if this instance could not be serialized
   */
  public java.nio.ByteBuffer toByteBuffer() throws java.io.IOException {
    return ENCODER.encode(this);
  }

  /**
   * Deserializes a Configuration from a ByteBuffer.
   * @param b a byte buffer holding serialized data for an instance of this class
   * @return a Configuration instance decoded from the given buffer
   * @throws java.io.IOException if the given bytes could not be deserialized into an instance of this class
   */
  public static Configuration fromByteBuffer(
      java.nio.ByteBuffer b) throws java.io.IOException {
    return DECODER.decode(b);
  }

   private java.lang.String value;
   private java.lang.String version;

  /**
   * Default constructor.  Note that this does not initialize fields
   * to their default values from the schema.  If that is desired then
   * one should use <code>newBuilder()</code>.
   */
  public Configuration() {}

  /**
   * All-args constructor.
   * @param value The new value for value
   * @param version The new value for version
   */
  public Configuration(java.lang.String value, java.lang.String version) {
    this.value = value;
    this.version = version;
  }

  public org.apache.avro.specific.SpecificData getSpecificData() { return MODEL$; }
  public org.apache.avro.Schema getSchema() { return SCHEMA$; }
  // Used by DatumWriter.  Applications should not call.
  public java.lang.Object get(int field$) {
    switch (field$) {
    case 0: return value;
    case 1: return version;
    default: throw new IndexOutOfBoundsException("Invalid index: " + field$);
    }
  }

  // Used by DatumReader.  Applications should not call.
  @SuppressWarnings(value="unchecked")
  public void put(int field$, java.lang.Object value$) {
    switch (field$) {
    case 0: value = value$ != null ? value$.toString() : null; break;
    case 1: version = value$ != null ? value$.toString() : null; break;
    default: throw new IndexOutOfBoundsException("Invalid index: " + field$);
    }
  }

  /**
   * Gets the value of the 'value' field.
   * @return The value of the 'value' field.
   */
  public java.lang.String getValue() {
    return value;
  }


  /**
   * Sets the value of the 'value' field.
   * @param value the value to set.
   */
  public void setValue(java.lang.String value) {
    this.value = value;
  }

  /**
   * Gets the value of the 'version' field.
   * @return The value of the 'version' field.
   */
  public java.lang.String getVersion() {
    return version;
  }


  /**
   * Sets the value of the 'version' field.
   * @param value the value to set.
   */
  public void setVersion(java.lang.String value) {
    this.version = value;
  }

  /**
   * Creates a new Configuration RecordBuilder.
   * @return A new Configuration RecordBuilder
   */
  public static net.corda.data.config.Configuration.Builder newBuilder() {
    return new net.corda.data.config.Configuration.Builder();
  }

  /**
   * Creates a new Configuration RecordBuilder by copying an existing Builder.
   * @param other The existing builder to copy.
   * @return A new Configuration RecordBuilder
   */
  public static net.corda.data.config.Configuration.Builder newBuilder(net.corda.data.config.Configuration.Builder other) {
    if (other == null) {
      return new net.corda.data.config.Configuration.Builder();
    } else {
      return new net.corda.data.config.Configuration.Builder(other);
    }
  }

  /**
   * Creates a new Configuration RecordBuilder by copying an existing Configuration instance.
   * @param other The existing instance to copy.
   * @return A new Configuration RecordBuilder
   */
  public static net.corda.data.config.Configuration.Builder newBuilder(net.corda.data.config.Configuration other) {
    if (other == null) {
      return new net.corda.data.config.Configuration.Builder();
    } else {
      return new net.corda.data.config.Configuration.Builder(other);
    }
  }

  /**
   * RecordBuilder for Configuration instances.
   */
  @org.apache.avro.specific.AvroGenerated
  public static class Builder extends org.apache.avro.specific.SpecificRecordBuilderBase<Configuration>
    implements org.apache.avro.data.RecordBuilder<Configuration> {

    private java.lang.String value;
    private java.lang.String version;

    /** Creates a new Builder */
    private Builder() {
      super(SCHEMA$);
    }

    /**
     * Creates a Builder by copying an existing Builder.
     * @param other The existing Builder to copy.
     */
    private Builder(net.corda.data.config.Configuration.Builder other) {
      super(other);
      if (isValidValue(fields()[0], other.value)) {
        this.value = data().deepCopy(fields()[0].schema(), other.value);
        fieldSetFlags()[0] = other.fieldSetFlags()[0];
      }
      if (isValidValue(fields()[1], other.version)) {
        this.version = data().deepCopy(fields()[1].schema(), other.version);
        fieldSetFlags()[1] = other.fieldSetFlags()[1];
      }
    }

    /**
     * Creates a Builder by copying an existing Configuration instance
     * @param other The existing instance to copy.
     */
    private Builder(net.corda.data.config.Configuration other) {
      super(SCHEMA$);
      if (isValidValue(fields()[0], other.value)) {
        this.value = data().deepCopy(fields()[0].schema(), other.value);
        fieldSetFlags()[0] = true;
      }
      if (isValidValue(fields()[1], other.version)) {
        this.version = data().deepCopy(fields()[1].schema(), other.version);
        fieldSetFlags()[1] = true;
      }
    }

    /**
      * Gets the value of the 'value' field.
      * @return The value.
      */
    public java.lang.String getValue() {
      return value;
    }


    /**
      * Sets the value of the 'value' field.
      * @param value The value of 'value'.
      * @return This builder.
      */
    public net.corda.data.config.Configuration.Builder setValue(java.lang.String value) {
      validate(fields()[0], value);
      this.value = value;
      fieldSetFlags()[0] = true;
      return this;
    }

    /**
      * Checks whether the 'value' field has been set.
      * @return True if the 'value' field has been set, false otherwise.
      */
    public boolean hasValue() {
      return fieldSetFlags()[0];
    }


    /**
      * Clears the value of the 'value' field.
      * @return This builder.
      */
    public net.corda.data.config.Configuration.Builder clearValue() {
      value = null;
      fieldSetFlags()[0] = false;
      return this;
    }

    /**
      * Gets the value of the 'version' field.
      * @return The value.
      */
    public java.lang.String getVersion() {
      return version;
    }


    /**
      * Sets the value of the 'version' field.
      * @param value The value of 'version'.
      * @return This builder.
      */
    public net.corda.data.config.Configuration.Builder setVersion(java.lang.String value) {
      validate(fields()[1], value);
      this.version = value;
      fieldSetFlags()[1] = true;
      return this;
    }

    /**
      * Checks whether the 'version' field has been set.
      * @return True if the 'version' field has been set, false otherwise.
      */
    public boolean hasVersion() {
      return fieldSetFlags()[1];
    }


    /**
      * Clears the value of the 'version' field.
      * @return This builder.
      */
    public net.corda.data.config.Configuration.Builder clearVersion() {
      version = null;
      fieldSetFlags()[1] = false;
      return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Configuration build() {
      try {
        Configuration record = new Configuration();
        record.value = fieldSetFlags()[0] ? this.value : (java.lang.String) defaultValue(fields()[0]);
        record.version = fieldSetFlags()[1] ? this.version : (java.lang.String) defaultValue(fields()[1]);
        return record;
      } catch (org.apache.avro.AvroMissingFieldException e) {
        throw e;
      } catch (java.lang.Exception e) {
        throw new org.apache.avro.AvroRuntimeException(e);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static final org.apache.avro.io.DatumWriter<Configuration>
    WRITER$ = (org.apache.avro.io.DatumWriter<Configuration>)MODEL$.createDatumWriter(SCHEMA$);

  @Override public void writeExternal(java.io.ObjectOutput out)
    throws java.io.IOException {
    WRITER$.write(this, SpecificData.getEncoder(out));
  }

  @SuppressWarnings("unchecked")
  private static final org.apache.avro.io.DatumReader<Configuration>
    READER$ = (org.apache.avro.io.DatumReader<Configuration>)MODEL$.createDatumReader(SCHEMA$);

  @Override public void readExternal(java.io.ObjectInput in)
    throws java.io.IOException {
    READER$.read(this, SpecificData.getDecoder(in));
  }

  @Override protected boolean hasCustomCoders() { return true; }

  @Override public void customEncode(org.apache.avro.io.Encoder out)
    throws java.io.IOException
  {
    out.writeString(this.value);

    out.writeString(this.version);

  }

  @Override public void customDecode(org.apache.avro.io.ResolvingDecoder in)
    throws java.io.IOException
  {
    org.apache.avro.Schema.Field[] fieldOrder = in.readFieldOrderIfDiff();
    if (fieldOrder == null) {
      this.value = in.readString();

      this.version = in.readString();

    } else {
      for (int i = 0; i < 2; i++) {
        switch (fieldOrder[i].pos()) {
        case 0:
          this.value = in.readString();
          break;

        case 1:
          this.version = in.readString();
          break;

        default:
          throw new java.io.IOException("Corrupt ResolvingDecoder.");
        }
      }
    }
  }
}










