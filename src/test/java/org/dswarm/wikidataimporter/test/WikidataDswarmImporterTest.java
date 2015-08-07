/**
 * Copyright (C) 2013 – 2015 SLUB Dresden & Avantgarde Labs GmbH (<code@dswarm.org>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.dswarm.wikidataimporter.test;

import java.net.URL;

import com.google.common.io.Resources;
import org.junit.Test;

import org.dswarm.wikidataimporter.Executer;

/**
 * @author tgaengler
 */
public class WikidataDswarmImporterTest {

	@Test
	public void wikidataDswarmImporterTest() {

		final URL resourceURL = Resources.getResource("lic_dmp_01_v1.csv.gson");

		final String[] args = new String[] {
				resourceURL.getPath()
		};

		Executer.main(args);
	}

}
