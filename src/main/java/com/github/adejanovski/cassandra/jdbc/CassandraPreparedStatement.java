/*
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package com.github.adejanovski.cassandra.jdbc;

import static com.github.adejanovski.cassandra.jdbc.Utils.NO_RESULTSET;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.sql.Blob;
import java.sql.Date;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLNonTransientException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.LocalDate;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.exceptions.CodecNotFoundException;
import com.datastax.driver.core.exceptions.InvalidTypeException;
import com.google.common.collect.Lists;
import com.google.common.io.CharSource;
import com.google.common.io.CharStreams;

class CassandraPreparedStatement extends CassandraStatement implements PreparedStatement
{
    private static final Logger LOG = LoggerFactory.getLogger(CassandraPreparedStatement.class);

    /** the count of bound variable markers (?) encountered in the parse o the CQL server-side */
    private int count;

    /** a Map of the current bound values encountered in setXXX methods */
    private Map<Integer, Object> bindValues = new LinkedHashMap<Integer, Object>();
    private com.datastax.driver.core.PreparedStatement stmt ;
    
    private BoundStatement statement;
    private ArrayList<BoundStatement> batchStatements;
    //private BoundStatement boundStatement;
    //private CassandraResultSet currentResultSet=null;
    protected ResultSet currentResultSet = null;


    CassandraPreparedStatement(CassandraConnection con, String cql) throws SQLException
    {    	
        this(con, cql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }
    
    
    CassandraPreparedStatement(CassandraConnection con,
        String cql,
        int rsType,
        int rsConcurrency,
        int rsHoldability
        ) throws SQLException
    {
       super(con,cql,rsType,rsConcurrency,rsHoldability);       
       
       if (LOG.isTraceEnabled()) LOG.trace("CQL: " + this.cql);
       try
       {
    	   stmt = this.connection.getSession().prepare(cql); 
    	   this.statement = new BoundStatement(stmt);    	   
    	   batchStatements = Lists.newArrayList();
    	   count = cql.length() - cql.replace("?", "").length();   	   
       }
       catch (Exception e)
       {
           throw new SQLTransientException(e);
       }
    }
    
    String getCql()
    {
        return cql;
    }

    @SuppressWarnings("boxing")
	private final void checkIndex(int index) throws SQLException
    {
        if (index > count) throw new SQLRecoverableException(String.format("the column index : %d is greater than the count of bound variable markers in the CQL: %d",
            index,
            count));
        if (index < 1) throw new SQLRecoverableException(String.format("the column index must be a positive number : %d", index));
    }

    /* private List<ByteBuffer> getBindValues() throws SQLException
    {
    	this.statement.b
        List<ByteBuffer> values = new ArrayList<ByteBuffer>();
        if (bindValues.size() != count) throw new SQLRecoverableException(
            String.format("the number of bound variables: %d must match the count of bound variable markers in the CQL: %d",
            bindValues.size(),
            count));

        for (int i = 1; i <= count; i++)
        {
            ByteBuffer value = bindValues.get(i);
            if (value == null) throw new SQLRecoverableException(String.format("the bound value for index: %d was not set", i));
            values.add(value);
        }
        return values;
    }
*/

    public void close()
    {
        try{
        	connection.removeStatement(this);
        }catch(Exception e){
        	
        }

        //connection = null;
    }
        

    private void doExecute() throws SQLException
    {
        if (LOG.isTraceEnabled()) LOG.trace("CQL: " + cql);
        try
        {
            resetResults();
            if (this.connection.debugMode) System.out.println("CQL: "+ cql);     
            if(this.statement.getFetchSize()==0)
            		// force paging to avoid timeout and node harm...
            		this.statement.setFetchSize(100);
            this.statement.setConsistencyLevel(this.connection.defaultConsistencyLevel);
            for(int i=0; i<this.statement.preparedStatement().getVariables().size(); i++){
            	// Set parameters to null if unset
            	if(!this.statement.isSet(i)){
            		this.statement.setToNull(i);
            	}
            }
            currentResultSet = new CassandraResultSet(this, this.connection.getSession().execute(this.statement));
        }
        catch (Exception e)
        {
            throw new SQLTransientException(e);
        }
    }

    public void addBatch() throws SQLException
    {
        batchStatements.add(statement);
        this.statement = new BoundStatement(stmt);
        if(batchStatements.size()>MAX_ASYNC_QUERIES){
        	throw new SQLNonTransientException("Too many queries at once (" + batchStatements.size() + "). You must split your queries into more batches !");
        }
    }
    
    public int[] executeBatch() throws SQLException
    {
    	int[] returnCounts= new int[batchStatements.size()];
    	try{	    	
	    	List<ResultSetFuture> futures = new ArrayList<ResultSetFuture>();
	    	if (this.connection.debugMode) System.out.println("CQL statements : "+ batchStatements.size());
	    	for(BoundStatement q:batchStatements){
	    		for(int i=0; i<q.preparedStatement().getVariables().size(); i++){
	    			// Set parameters to null if unset
	    			if(!q.isSet(i)){
	            		q.setToNull(i);
	            	}
	            }
	    		if (this.connection.debugMode) System.out.println("CQL: "+ cql);
	    		q.setConsistencyLevel(this.connection.defaultConsistencyLevel);
	    		
	    		ResultSetFuture resultSetFuture = this.connection.getSession().executeAsync(q);
	    		futures.add(resultSetFuture);
	    	}
			
	    	int i=0;
			for (ResultSetFuture future : futures){
				future.getUninterruptibly();
				returnCounts[i]=1;
				i++;
			}
			
			
			// empty batch statement list after execution
			batchStatements = Lists.newArrayList();
    	}catch(Exception e){
    		// empty batch statement list after execution even if it failed...
    		batchStatements = Lists.newArrayList();
    		throw new SQLTransientException(e);
    	}
        
    	return returnCounts;
    }


    public void clearParameters() throws SQLException
    {
        checkNotClosed();
        bindValues.clear();
    }


    public boolean execute() throws SQLException
    {
        checkNotClosed();
        doExecute();
        return !(currentResultSet == null);
    }


    public ResultSet executeQuery() throws SQLException
    {
        checkNotClosed();
        doExecute();
        if (currentResultSet == null) throw new SQLNonTransientException(NO_RESULTSET);
        return currentResultSet;
    }


    public int executeUpdate() throws SQLException
    {
        checkNotClosed();
        doExecute();
        //if (currentResultSet != null) throw new SQLNonTransientException(NO_UPDATE_COUNT);
        // no update count available with the Datastax java driver
        return 0;
    }


    public ResultSetMetaData getMetaData() throws SQLException
    {
        return null;
    }


    public ParameterMetaData getParameterMetaData() throws SQLException
    {
        return null;
    }


    public void setBigDecimal(int parameterIndex, BigDecimal decimal) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        this.statement.setDecimal(parameterIndex-1, decimal);
        //bindValues.put(parameterIndex, decimal == null ? null : JdbcDecimal.instance.decompose(decimal));
    }


    public void setBoolean(int parameterIndex, boolean truth) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        //bindValues.put(parameterIndex, JdbcBoolean.instance.decompose(truth));
        this.statement.setBool(parameterIndex-1, truth);
    }


    public void setByte(int parameterIndex, byte b) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        //bindValues.put(parameterIndex, JdbcInteger.instance.decompose(BigInteger.valueOf(b)));
        //this.statement.setBytes(parameterIndex, ByteBuffer.);
    }


    public void setBytes(int parameterIndex, byte[] bytes) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        this.statement.setBytes(parameterIndex-1, ByteBuffer.wrap(bytes));
        //bindValues.put(parameterIndex, bytes == null ? null : ByteBuffer.wrap(bytes));
    }


    public void setDate(int parameterIndex, Date value) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        // date type data is handled as an 8 byte Long value of milliseconds since the epoch (handled in decompose() )
        //bindValues.put(parameterIndex, value == null ? null : JdbcDate.instance.decompose(value));
        this.statement.setTimestamp(parameterIndex-1, value);
    }


    public void setDate(int parameterIndex, Date date, Calendar cal) throws SQLException
    {
        // silently ignore the calendar argument it is not useful for the Cassandra implementation
        setDate(parameterIndex, date);
    }


    public void setDouble(int parameterIndex, double decimal) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        //bindValues.put(parameterIndex, JdbcDouble.instance.decompose(decimal));
        this.statement.setDouble(parameterIndex-1, decimal);
    }


    public void setFloat(int parameterIndex, float decimal) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        //bindValues.put(parameterIndex, JdbcFloat.instance.decompose(decimal));
        this.statement.setFloat(parameterIndex-1, decimal);
    }


    @SuppressWarnings("cast")
	public void setInt(int parameterIndex, int integer) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        //bindValues.put(parameterIndex, JdbcInt32.instance.decompose(integer));
        try{
        	this.statement.setInt(parameterIndex-1, integer);
        }catch(CodecNotFoundException e){        	        
    		if(e.getMessage().contains("Codec not found for requested operation: [varint <-> java.lang.Integer]")){
    			this.statement.setVarint(parameterIndex-1, BigInteger.valueOf((long)integer));
    		}
    	}
        
    }


    public void setLong(int parameterIndex, long bigint) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        //bindValues.put(parameterIndex, JdbcLong.instance.decompose(bigint));
        this.statement.setLong(parameterIndex-1, bigint);
    }


    public void setNString(int parameterIndex, String value) throws SQLException
    {
        // treat like a String
        setString(parameterIndex, value);
    }


    public void setNull(int parameterIndex, int sqlType) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        // silently ignore type for cassandra... just store an empty String
        this.statement.setToNull(parameterIndex-1);
        
    }


    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException
    {
        // silently ignore type and type name for cassandra... just store an empty BB
        setNull(parameterIndex, sqlType);
    }

    

    public void setObject(int parameterIndex, Object object) throws SQLException
    {
   	
    	int targetType=0;
    	if(object.getClass().equals(java.lang.Long.class)){
    		targetType = Types.BIGINT;    		
    	} else if(object.getClass().equals(java.io.ByteArrayInputStream.class)){
    		targetType = Types.BINARY;
    	} else if(object.getClass().equals(java.lang.String.class)){
    		targetType = Types.VARCHAR;
    	} else if(object.getClass().equals(java.lang.Boolean.class)){
    		targetType = Types.BOOLEAN;
    	}else if(object.getClass().equals(java.math.BigDecimal.class)){
    		targetType = Types.DECIMAL;
    	}else if(object.getClass().equals(java.lang.Double.class)){
    		targetType = Types.DOUBLE;
    	}else if(object.getClass().equals(java.lang.Float.class)){
    		targetType = Types.FLOAT;
    	}else if(object.getClass().equals(java.net.Inet4Address.class)){
    		targetType = Types.OTHER;
    	}else if(object.getClass().equals(java.lang.Integer.class)){
    		targetType = Types.INTEGER;
    	}else if(object.getClass().equals(java.lang.String.class)){
    		targetType = Types.VARCHAR;
    	}else if(object.getClass().equals(java.sql.Timestamp.class)){
    		targetType = Types.TIMESTAMP;
    	}else if(object.getClass().equals(java.util.UUID.class)){
    		targetType = Types.ROWID;
    	}else{
    		targetType = Types.OTHER;
    	}
        setObject(parameterIndex, object, targetType, 0);        
    }

    public void setObject(int parameterIndex, Object object, int targetSqlType) throws SQLException
    {
        setObject(parameterIndex, object, targetSqlType, 0);
    }

    @SuppressWarnings({ "boxing", "unchecked" })
	public final void setObject(int parameterIndex, Object object, int targetSqlType, int scaleOrLength) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        
        switch(targetSqlType){          
        case Types.VARCHAR:
        	this.statement.setString(parameterIndex-1, object.toString());
        	break;
        case Types.BIGINT:
        	this.statement.setLong(parameterIndex-1, Long.parseLong(object.toString()));
        	break;
        case Types.BINARY:        	
        	byte[] array = new byte[((java.io.ByteArrayInputStream)object).available()];
        	try {
				((java.io.ByteArrayInputStream)object).read(array);
			} catch (IOException e1) {
				LOG.warn("Exception while setting object of BINARY type", e1);
			}
        	this.statement.setBytes(parameterIndex-1, (ByteBuffer.wrap((array))));
        	break;        	
        case Types.BOOLEAN:
        	this.statement.setBool(parameterIndex-1, (Boolean)object);
        	break;
        case Types.CHAR:
        	this.statement.setString(parameterIndex-1, object.toString());
        	break;
        case Types.CLOB:
        	this.statement.setString(parameterIndex-1, object.toString());
        	break;          
        case Types.TIMESTAMP:
        	this.statement.setTimestamp(parameterIndex-1, (Timestamp)object);
        	break;
        case Types.DECIMAL:
        	this.statement.setDecimal(parameterIndex-1, (BigDecimal)object);
        	break;
        case Types.DOUBLE:
        	this.statement.setDouble(parameterIndex-1, (Double)object);
        	break;
        case Types.FLOAT:
        	this.statement.setFloat(parameterIndex-1, (Float)object);
        	break;
        case Types.INTEGER:
        	try{
        		this.statement.setInt(parameterIndex-1, (Integer)object);
        	}catch(CodecNotFoundException e){
        		if(e.getMessage().contains("varint")){ // sucks but works
        			this.statement.setVarint(parameterIndex-1, BigInteger.valueOf(Long.parseLong(object.toString())));
        		}
        	}
        	break;
        case Types.DATE:
        	this.statement.setTimestamp(parameterIndex-1, (Date)object);
        	break;
        case Types.ROWID:
        	this.statement.setUUID(parameterIndex-1, (java.util.UUID)object);
        	break;
        case Types.OTHER:
        	if(object.getClass().equals(com.datastax.driver.core.TupleValue.class)){
        		this.statement.setTupleValue(parameterIndex-1, (com.datastax.driver.core.TupleValue) object);
        	}
        	if(object.getClass().equals(java.util.UUID.class)){
        		this.statement.setUUID(parameterIndex-1, (java.util.UUID) object);
        	}
        	if(object.getClass().equals(java.net.InetAddress.class) || object.getClass().equals(java.net.Inet4Address.class)){
        		this.statement.setInet(parameterIndex-1, (InetAddress) object);
        	}
        	else if ( List.class.isAssignableFrom(object.getClass()))
            {
              this.statement.setList(parameterIndex-1,handleAsList(object.getClass(), object));  
            }
            else if ( Set.class.isAssignableFrom(object.getClass()))
            {
            	this.statement.setSet(parameterIndex-1,handleAsSet(object.getClass(), object)); 
            }
            else if ( Map.class.isAssignableFrom(object.getClass()))
            {
            	this.statement.setMap(parameterIndex-1,handleAsMap(object.getClass(), object));              
            } 
                    	
        	break;
        }
        
        	
        
    }

    public void setRowId(int parameterIndex, RowId value) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        this.statement.setToNull(parameterIndex-1);
    }


    public void setShort(int parameterIndex, short smallint) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        this.statement.setInt(parameterIndex-1, smallint);
    }

    

    public void setString(int parameterIndex, String value) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        try{        	
        	this.statement.setString(parameterIndex-1, value);
        }catch(CodecNotFoundException e){        	       
        	// Big ugly hack in order to parse string representations of collections
        	// Yes, I'm ashamed...
           	if(e.getMessage().contains("set<")){
        		String itemType = e.getMessage().substring(e.getMessage().indexOf("<")+1, e.getMessage().indexOf(">"));
        		this.statement.setSet(parameterIndex-1, Utils.parseSet(itemType, value));        		        		        		
    		}else if(e.getMessage().contains("list<")){
        		String itemType = e.getMessage().substring(e.getMessage().indexOf("<")+1, e.getMessage().indexOf(">"));
        		this.statement.setList(parameterIndex-1, Utils.parseList(itemType, value));        	    		
    		}else if(e.getMessage().contains("map<")){
        		String[] kvTypes = e.getMessage().substring(e.getMessage().indexOf("<")+1, e.getMessage().indexOf(">")).replace(" ", "").split(",");
        		this.statement.setMap(parameterIndex-1, Utils.parseMap(kvTypes[0],kvTypes[1], value));
    		}
        }
        
    }


    @SuppressWarnings("boxing")
	public void setTime(int parameterIndex, Time value) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        // time type data is handled as an 8 byte Long value of milliseconds since the epoch
        bindValues.put(parameterIndex,
            value == null ? null : JdbcLong.instance.decompose(Long.valueOf(value.getTime())));
    }


    public void setTime(int parameterIndex, Time value, Calendar cal) throws SQLException
    {
        // silently ignore the calendar argument it is not useful for the Cassandra implementation
        setTime(parameterIndex, value);
    }


    public void setTimestamp(int parameterIndex, Timestamp value) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        // timestamp type data is handled as an 8 byte Long value of milliseconds since the epoch. Nanos are not supported and are ignored
        this.statement.setTimestamp(parameterIndex-1, new Date(value.getTime()));
    }


    public void setTimestamp(int parameterIndex, Timestamp value, Calendar cal) throws SQLException
    {
        // silently ignore the calendar argument it is not useful for the Cassandra implementation
        setTimestamp(parameterIndex, value);
    }


    public void setURL(int parameterIndex, URL value) throws SQLException
    {
        checkNotClosed();
        checkIndex(parameterIndex);
        // URl type data is handled as an string
        String url = value.toString();
        setString(parameterIndex, url);
    }
    
    public ResultSet getResultSet() throws SQLException
    {
        checkNotClosed();
        return currentResultSet;
    }
    
    @SuppressWarnings({ "unchecked", "rawtypes" })
	private static final <T> List handleAsList(Class<? extends Object> objectClass, Object object)
    {
        if (!List.class.isAssignableFrom(objectClass)) return null;        
        return (List<T>) object.getClass().cast(object);
                
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
	private static final <T> Set handleAsSet(Class<? extends Object> objectClass, Object object)
    {
        if (!Set.class.isAssignableFrom(objectClass)) return null;       
        
        return (Set<T>) object.getClass().cast(object);        
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
	private static final <K,V> HashMap  handleAsMap(Class<? extends Object> objectClass, Object object)
    {
        if (!Map.class.isAssignableFrom(objectClass)) return null;
       
        return (HashMap<K,V>) object.getClass().cast(object);
        
    }
    
    @SuppressWarnings("rawtypes")
	private static final Class<?> getCollectionElementType(Object maybeCollection)
    {
        Collection trial = (Collection) maybeCollection;
        if (trial.isEmpty()) return trial.getClass();
		return trial.iterator().next().getClass();
    }
   
    @SuppressWarnings({ "unused", "rawtypes" })
	private static final Class<?> getKeyElementType(Object maybeMap)
    {
        return getCollectionElementType(((Map) maybeMap).keySet());
    }
   
    @SuppressWarnings({ "unused", "rawtypes" })
	private static final Class<?> getValueElementType(Object maybeMap)
    {
        return getCollectionElementType(((Map) maybeMap).values());
    }


	@SuppressWarnings("boxing")
	@Override
	public void setBlob(int parameterIndex, Blob value) throws SQLException {
	
		InputStream in = value.getBinaryStream();

		setBlob(parameterIndex, in);

	}


	@Override
	public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
		int nRead;
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] data = new byte[16384];

		try {
			while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
			  buffer.write(data, 0, nRead);
			}
		} catch (IOException e) {
			throw new SQLNonTransientException(e);
		}

		try {
			buffer.flush();
		} catch (IOException e) {
			throw new SQLNonTransientException(e);
		}

		this.statement.setBytes(parameterIndex-1, (ByteBuffer.wrap((buffer.toByteArray()))));
		
	}


	@Override
	public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
		try {
		    String targetString = CharStreams.toString(reader);
		    reader.close();
		    
		    setString(parameterIndex, targetString);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			throw new SQLNonTransientException(e);
		}
		
	}
   
}
