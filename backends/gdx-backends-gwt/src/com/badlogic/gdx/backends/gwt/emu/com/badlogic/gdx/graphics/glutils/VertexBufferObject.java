/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
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

package com.badlogic.gdx.graphics.glutils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.NumberUtils;

/**
 * <p>
 * A {@link VertexData} implementation based on OpenGL vertex buffer objects.
 * </p>
 * 
 * <p>
 * If the OpenGL ES context was lost you can call {@link #invalidate()} to recreate a new OpenGL vertex buffer object. This class
 * can be used seamlessly with OpenGL ES 1.x and 2.0.
 * </p>
 * 
 * <p>
 * In case OpenGL ES 2.0 is used in the application the data is bound via glVertexAttribPointer() according to the attribute
 * aliases specified via {@link VertexAttributes} in the constructor.
 * </p>
 * 
 * <p>
 * Uses indirect Buffers on Android 1.5/1.6 to fix GC invocation due to leaking PlatformAddress instances.
 * </p>
 * 
 * <p>
 * VertexBufferObjects must be disposed via the {@link #dispose()} method when no longer needed
 * </p>
 * 
 * @author mzechner, Dave Clayton <contact@redskyforge.com> */
public class VertexBufferObject implements VertexData {
	final VertexAttributes attributes;
	final FloatBuffer buffer;
	final IntBuffer intBuffer;
	int bufferHandle;
	final boolean isStatic;
	final int usage;
	boolean isDirty = false;
	boolean isBound = false;

	/** Constructs a new interleaved VertexBufferObject.
	 * 
	 * @param isStatic whether the vertex data is static.
	 * @param numVertices the maximum number of vertices
	 * @param attributes the {@link VertexAttribute}s. */
	public VertexBufferObject (boolean isStatic, int numVertices, VertexAttribute... attributes) {
		this(isStatic, numVertices, new VertexAttributes(attributes));
	}

	/** Constructs a new interleaved VertexBufferObject.
	 * 
	 * @param isStatic whether the vertex data is static.
	 * @param numVertices the maximum number of vertices
	 * @param attributes the {@link VertexAttributes}. */
	public VertexBufferObject (boolean isStatic, int numVertices, VertexAttributes attributes) {
		this.isStatic = isStatic;
		this.attributes = attributes;

		ByteBuffer byteBuffer = BufferUtils.newByteBuffer(this.attributes.vertexSize * numVertices);
		buffer = byteBuffer.asFloatBuffer();
		buffer.flip();
		intBuffer = byteBuffer.asIntBuffer();
		intBuffer.flip();
		bufferHandle = Gdx.gl20.glGenBuffer();
		usage = isStatic ? GL20.GL_STATIC_DRAW : GL20.GL_DYNAMIC_DRAW;
	}

	@Override
	public VertexAttributes getAttributes () {
		return attributes;
	}

	@Override
	public boolean isArray () {
		return false;
	}

	@Override
	public int getNumVertices () {
		return buffer.limit() / (attributes.vertexSize / 4);
	}

	@Override
	public int getNumMaxVertices () {
		return buffer.capacity() / (attributes.vertexSize / 4);
	}

	/** @deprecated use {@link #getBuffer(boolean)} instead */
	@Override
	@Deprecated
	public FloatBuffer getBuffer () {
		isDirty = true;
		return buffer;
	}

	@Override
	public FloatBuffer getBuffer (boolean forWriting) {
		isDirty |= forWriting;
		return buffer;
	}

	private void bufferChanged () {
		if (isBound) {
			Gdx.gl20.glBufferData(GL20.GL_ARRAY_BUFFER, buffer.limit(), buffer, usage);
			isDirty = false;
		}
	}

	private final IntArray array = new IntArray(512);

	@Override
	public void setVertices (float[] vertices, int offset, int count) {
		array.clear();
		for (int i = 0; i < count; i++) {
			array.add(NumberUtils.floatToIntBits(vertices[i + offset]));
		}
		setVertices(array.items, 0, count);
		/*
		 * isDirty = true; BufferUtils.copy(vertices, buffer, count, offset); buffer.position(0); buffer.limit(count);
		 * bufferChanged();
		 */
	}

	@Override
	public void updateVertices (int targetOffset, float[] vertices, int sourceOffset, int count) {
		array.clear();
		for (int i = 0; i < count; i++) {
			array.add(NumberUtils.floatToIntBits(vertices[i + sourceOffset]));
		}
		updateVertices(targetOffset, array.items, 0, count);

		/*
		 * isDirty = true; final int pos = buffer.position(); buffer.position(targetOffset); BufferUtils.copy(vertices,
		 * sourceOffset, count, buffer); buffer.position(pos); bufferChanged();
		 */
	}

	@Override
	public void setVertices (int[] vertices, int offset, int count) {
		isDirty = true;
		intBuffer.position(0);
		BufferUtils.copy(vertices, offset, intBuffer, count);
		intBuffer.limit(count);
		buffer.position(0);
		buffer.limit(count);
		bufferChanged();
	}

	@Override
	public void updateVertices (int targetOffset, int[] vertices, int sourceOffset, int count) {
		isDirty = true;
		intBuffer.position(targetOffset);
		intBuffer.limit(buffer.limit());
		BufferUtils.copy(vertices, sourceOffset, count, intBuffer);
		buffer.limit(intBuffer.limit());
		bufferChanged();
	}

	/** Binds this VertexBufferObject for rendering via glDrawArrays or glDrawElements
	 * 
	 * @param shader the shader */
	@Override
	public void bind (ShaderProgram shader) {
		bind(shader, null);
	}

	@Override
	public void bind (ShaderProgram shader, int[] locations) {
		final GL20 gl = Gdx.gl20;

		gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, bufferHandle);
		if (isDirty) {
			gl.glBufferData(GL20.GL_ARRAY_BUFFER, buffer.limit(), buffer, usage);
			isDirty = false;
		}

		final int numAttributes = attributes.size();
		if (locations == null) {
			for (int i = 0; i < numAttributes; i++) {
				final VertexAttribute attribute = attributes.get(i);
				final int location = shader.getAttributeLocation(attribute.alias);
				if (location < 0) continue;
				shader.enableVertexAttribute(location);
				shader.setVertexAttribute(location, attribute.numComponents, attribute.type, attribute.normalized,
					attributes.vertexSize, attribute.offset);
			}
		} else {
			for (int i = 0; i < numAttributes; i++) {
				final VertexAttribute attribute = attributes.get(i);
				final int location = locations[i];
				if (location < 0) continue;
				shader.enableVertexAttribute(location);
				shader.setVertexAttribute(location, attribute.numComponents, attribute.type, attribute.normalized,
					attributes.vertexSize, attribute.offset);
			}
		}
		isBound = true;
	}

	/** Unbinds this VertexBufferObject.
	 * 
	 * @param shader the shader */
	@Override
	public void unbind (final ShaderProgram shader) {
		unbind(shader, null);
	}

	@Override
	public void unbind (final ShaderProgram shader, final int[] locations) {
		final GL20 gl = Gdx.gl20;
		final int numAttributes = attributes.size();
		if (locations == null) {
			for (int i = 0; i < numAttributes; i++) {
				shader.disableVertexAttribute(attributes.get(i).alias);
			}
		} else {
			for (int i = 0; i < numAttributes; i++) {
				final int location = locations[i];
				if (location >= 0) shader.disableVertexAttribute(location);
			}
		}
		gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
		isBound = false;
	}

	/** Invalidates the VertexBufferObject so a new OpenGL buffer handle is created. Use this in case of a context loss. */
	public void invalidate () {
		bufferHandle = Gdx.gl20.glGenBuffer();
		isDirty = true;
	}

	/** Disposes of all resources this VertexBufferObject uses. */
	@Override
	public void dispose () {
		GL20 gl = Gdx.gl20;
		gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
		gl.glDeleteBuffer(bufferHandle);
		bufferHandle = 0;
	}
}
