<!--
/*
 * Copyright (C) 2021-present Gregory Hedlund
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 *    http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
!-->
<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
	xmlns:xs="http://www.w3.org/2001/XMLSchema"
	xmlns="http://phon.ling.mun.ca/ns/phonbank"
	exclude-result-prefixes="xs"
	version="1.0">
	
	<xsl:output method="xml"/>
	
	<xsl:template match="/">
<session xmlns="http://phon.ling.mun.ca/ns/phonbank" id="Session" corpus="Temp" version="PB1.2">
	<header>
		<date>1912-06-23</date>
		<language>eng</language>
		<media></media>
	</header>
	<participants>
		<xsl:for-each select="epac/audiofile/speakers/speaker">
			<xsl:call-template name="speaker">
				<xsl:with-param name="speakerid" select="@name"/>
				<xsl:with-param name="sex" select="@gender"/>
			</xsl:call-template>
		</xsl:for-each>
	</participants>
	<transcribers/>
	<userTiers/>
	<tierOrder>
		<tier tierName="Orthography" visible="true" locked="false" font="default"/>
		<tier tierName="IPA Target" visible="true" locked="false" font="default"/>
		<tier tierName="IPA Actual" visible="true" locked="false" font="default"/>
		<tier tierName="Notes" visible="true" locked="false" font="default"/>
		<tier tierName="Segment" visible="true" locked="false" font="default"/>
	</tierOrder>
	<transcript>
		<xsl:for-each select="epac/audiofile/segments/segment">
			<xsl:call-template name="segment">
				<xsl:with-param name="recordid">LIUM-<xsl:value-of select="position()"/></xsl:with-param>
				<xsl:with-param name="speakerid" select="@speaker"/>
				<xsl:with-param name="segstart" select="number(@start) * 1000"/>
				<xsl:with-param name="duration" select="((number(@end) * 1000) - (number(@start) * 1000))"></xsl:with-param>
			</xsl:call-template>
		</xsl:for-each>
	</transcript>
</session>
	</xsl:template>
		
	<xsl:template name="speaker">
		<xsl:param name="speakerid"/>
		<xsl:param name="sex"/>
		<participant>
			<xsl:attribute name="id"><xsl:value-of select="$speakerid"/></xsl:attribute>
			<role>Participant</role>
			<sex>
				<xsl:choose>
					<xsl:when test="$sex = 'F'">female</xsl:when>
					<xsl:when test="$sex = 'M'">male</xsl:when>
					<xsl:otherwise><xsl:value-of select="$sex"/></xsl:otherwise>
				</xsl:choose>
			</sex>
		</participant>
	</xsl:template>
	
	<xsl:template name="segment">
		<xsl:param name="recordid"/>
		<xsl:param name="speakerid"/>
		<xsl:param name="segstart"/>
		<xsl:param name="duration"/>
		<u>
			<xsl:attribute name="id"><xsl:value-of select="$recordid"/></xsl:attribute>
			<xsl:attribute name="speaker"><xsl:value-of select="$speakerid"/></xsl:attribute>
			<orthography>
				<g/>
			</orthography>
			<ipaTier form="model">
				<pg/>
			</ipaTier>
			<ipaTier form="actual">
				<pg/>
			</ipaTier>
			<alignment type="segmental">
				<ag length="0"/>
			</alignment>
			<segment unitType="ms">
				<xsl:attribute name="startTime"><xsl:value-of select="$segstart"/></xsl:attribute>
				<xsl:attribute name="duration"><xsl:value-of select="$duration"/></xsl:attribute>
			</segment>
		</u>
	</xsl:template>
	
</xsl:stylesheet>