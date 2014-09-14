/**
 * Copyright (c) 2012, Mayocat <hello@mayocat.org>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.mayocat.shop.catalog.api.v1

import com.google.common.base.Strings
import com.yammer.metrics.annotation.Timed
import groovy.transform.CompileStatic
import org.mayocat.Slugifier
import org.mayocat.authorization.annotation.Authorized
import org.mayocat.context.WebContext
import org.mayocat.entity.EntityData
import org.mayocat.entity.EntityDataLoader
import org.mayocat.image.model.Image
import org.mayocat.image.model.ImageGallery
import org.mayocat.model.PositionedEntity
import org.mayocat.rest.Resource
import org.mayocat.rest.api.object.LinkApiObject
import org.mayocat.shop.catalog.api.v1.object.CollectionApiObject
import org.mayocat.shop.catalog.api.v1.object.CollectionItemApiObject
import org.mayocat.shop.catalog.api.v1.object.CollectionTreeApiObject
import org.mayocat.shop.catalog.api.v1.object.ProductApiObject
import org.mayocat.shop.catalog.model.Product
import org.mayocat.shop.catalog.store.CollectionStore
import org.mayocat.shop.catalog.store.ProductStore
import org.mayocat.store.EntityAlreadyExistsException
import org.mayocat.store.InvalidEntityException
import org.slf4j.Logger
import org.xwiki.component.annotation.Component

import javax.inject.Inject
import javax.inject.Provider
import javax.ws.rs.*
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

/**
 * @version $Id$
 */
@Component("/api/collections")
@Path("/api/collections")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@CompileStatic
class CollectionApi implements Resource
{
    @Inject
    Provider<CollectionStore> collectionStore

    @Inject
    Provider<ProductStore> productStore


    @Inject
    Slugifier slugifier

    @Inject
    EntityDataLoader dataLoader

    @Inject
    WebContext context

    @Inject
    Logger logger

    @GET
    public CollectionTreeApiObject getCollectionTree()
    {
        List<org.mayocat.shop.catalog.model.Collection> collections = collectionStore.get().
                findAllOrderedByParentAndPosition()

        Map<UUID, CollectionItemApiObject> objects = [:]

        List<CollectionItemApiObject> roots = []

        collections.each({ org.mayocat.shop.catalog.model.Collection collection ->
            objects.put(collection.id, new CollectionItemApiObject().withCollection(collection))
        })

        collections.each({ org.mayocat.shop.catalog.model.Collection collection ->
            if (!collection.parentId) {
                roots << objects.get(collection.id)
            } else {
                if (!objects.containsKey(collection.parentId)) {
                    logger.warn("Collection with id {} references a parent that does not exists", collection.id)
                } else {
                    objects.get(collection.parentId).children << objects.get(collection.id)
                }
            }
        })

        // Inject parent slugs & hrefs

        Closure addParentsToItem;
        addParentsToItem = { CollectionItemApiObject item, List<CollectionItemApiObject> parents ->
            item.parentSlugs = parents.collect({ CollectionItemApiObject i -> i.slug })
            item.children.each({ CollectionItemApiObject children ->
                List<CollectionItemApiObject> childrenParents = []
                childrenParents.addAll(parents)
                childrenParents.add(0, item)
                addParentsToItem(children, childrenParents)
            })
        }

        Closure addLinks;
        addLinks = { CollectionItemApiObject item ->
            def parentsChain = item.parentSlugs.reverse().join('/collections/') + (item.parentSlugs.size() > 0 ? '/' : '')
            item._href = "/collections/elements/${parentsChain}${item.slug}"
            item._links = [
                    self: new LinkApiObject([href: item._href]),
                    products: new LinkApiObject([href: item._href + '/products'])
            ]

            item.children.each({ CollectionItemApiObject children ->
                addLinks(children)
            })
        }

        roots.each({ CollectionItemApiObject item ->
            addParentsToItem(item, [])
            addLinks(item)
        })

        new CollectionTreeApiObject([
                collections: roots
        ])
    }

    @PUT
    public void updateCollectionTree(CollectionTreeApiObject collectionTreeApiObject)
    {
        List<org.mayocat.shop.catalog.model.Collection> collections = collectionStore.get().
                findAllOrderedByParentAndPosition()

        List<PositionedEntity<org.mayocat.shop.catalog.model.Collection>> positionedCollections = []

        Closure processItem;
        processItem = { CollectionItemApiObject item, UUID parent, Integer position ->

            org.mayocat.shop.catalog.model.Collection collection = collections.find({
                org.mayocat.shop.catalog.model.Collection c -> c.id.toString() == item._id
            })
            if (!collection) {
                logger.warn("Trying to update a collection that does not exist");
            } else {
                collection.parentId = parent
                positionedCollections <<
                        new PositionedEntity<org.mayocat.shop.catalog.model.Collection>(collection, position)

                item.children.eachWithIndex({ CollectionItemApiObject entry, int i ->
                    processItem(entry, collection.id, i)
                })
            }
        }

        collectionTreeApiObject.collections.eachWithIndex({ CollectionItemApiObject item, int i ->
            processItem(item, null, i)
        })

        this.collectionStore.get().updateCollectionTree(positionedCollections);
    }

    @POST
    @Timed
    @Authorized
    public Response createCollection(CollectionApiObject collectionApiObject)
    {
        try {
            def collection = collectionApiObject.toCollection();

            // Set slug TODO: verify if provided slug is conform
            collection.slug = Strings.isNullOrEmpty(collectionApiObject.slug) ? slugifier.slugify(collection.title) :
                    collectionApiObject.slug

            org.mayocat.shop.catalog.model.Collection created = this.collectionStore.get().create(collection);

            // Respond with a created URI relative to this API URL.
            // This will add a location header like http://host/api/<version>/collections/items/my-created-collection
            return Response.created(new URI(created.slug)).build();
        } catch (InvalidEntityException e) {
            throw new com.yammer.dropwizard.validation.InvalidEntityException(e.message, e.errors);
        } catch (EntityAlreadyExistsException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("A Collection with this slug already exists\n").type(MediaType.TEXT_PLAIN_TYPE).build();
        } catch (URISyntaxException e) {
            throw new WebApplicationException(e);
        }
    }

    @Path("{slug}")
    @POST
    @Timed
    @Authorized
    public Response updateCollection(@PathParam("slug") String slug,
            CollectionApiObject collectionApiObject)
    {
        try {
            org.mayocat.shop.catalog.model.Collection collection = this.collectionStore.get().findBySlug(slug);
            if (collection == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("No collection with this slug could be found\n").type(MediaType.TEXT_PLAIN_TYPE).
                        build();
            } else {

                def id = collection.id
                def featuredImageId = collection.featuredImageId
                collection = collectionApiObject.toCollection()

                // ID and slugs are not update-able
                collection.id = id
                collection.slug = slug

                // Featured image is updated via the /images API only, set it back
                collection.featuredImageId = featuredImageId

                this.collectionStore.get().update(collection);

                return Response.ok().build();
            }
        } catch (InvalidEntityException e) {
            throw new com.yammer.dropwizard.validation.InvalidEntityException(e.message, e.errors);
        }
    }

    @GET
    @Path("{slug}")
    def getCollection(@PathParam("slug") String slug)
    {
        getCollectionInternal(slug)
    }

    @GET
    @Path("{parent1}/collections/{slug}")
    def getCollectionWithOneParent(
            @PathParam("parent1") String parent1,
            @PathParam("slug") String slug)
    {
        getCollectionInternal(parent1, slug)
    }

    @GET
    @Path("{parent2}/collections/{parent1}/{slug}")
    def getCollectionWithTwoParents(
            @PathParam("parent2") String parent2,
            @PathParam("parent1") String parent1,
            @PathParam("slug") String slug)
    {
        getCollectionInternal(parent2, parent1, slug)
    }

    @GET
    @Path("{parent3}/collections/{parent2}/collections/{parent1}/collections/{slug}")
    def getCollectionWithThreeParents(
            @PathParam("parent3") String parent3,
            @PathParam("parent2") String parent2,
            @PathParam("parent1") String parent1,
            @PathParam("slug") String slug)
    {
        getCollectionInternal(parent3, parent2, parent1, slug)
    }

    @POST
    @Path("{slug}/products")
    def addProductToCollection(@PathParam("slug") String slug, ProductApiObject productApiObject)
    {
        org.mayocat.shop.catalog.model.Collection collection = this.collectionStore.get().findBySlug(slug)
        String productSlug = productApiObject.slug
        Product product = this.productStore.get().findBySlug(productSlug)

        if (!collection || !product) {
            return Response.status(Response.Status.NOT_FOUND).build()
        }

        this.collectionStore.get().addEntityToCollection(collection, product)
    }

    @DELETE
    @Path("{collection}/products/{product}")
    def removeProductFromCollection(
            @PathParam("collection") String collectionSlug,
            @PathParam("product") Reference product)
    {

    }

    // -----------------------------------------------------------------------------------------------------------------

    def getCollectionInternal(String... slugsArray)
    {
        // Reverse array
        def slugs = slugsArray.reverse() as List<String>

        List<org.mayocat.shop.catalog.model.Collection> parents = []
        def index = 0

        while (slugs.size() > 1) {
            // Walk down the parents slug chain

            def parentSlug = slugs.pop()
            def parentCollection = this.collectionStore.get().
                    findBySlug(parentSlug, index > 0 ? parents[index - 1].id : null);

            if (!parentCollection) {
                // If any of the collection of the chain is not found, the collection is not found
                return Response.status(Response.Status.NOT_FOUND).build()
            }

            parents.push(parentCollection)
            index++
        }

        def slug = slugs[0]

        org.mayocat.shop.catalog.model.Collection collection = this.collectionStore.get().
                findBySlug(slug, parents.size() > 0 ? parents[-1].id : null);

        if (!collection) {
            return Response.status(Response.Status.NOT_FOUND).build()
        }

        EntityData<org.mayocat.shop.catalog.model.Collection> collectionData = dataLoader.load(collection)

        def gallery = collectionData.getData(ImageGallery)
        List<Image> images = gallery.isPresent() ? gallery.get().images : [] as List<Image>

        if (collection == null) {
            return Response.status(404).build();
        }

        CollectionApiObject collectionApiObject = new CollectionApiObject([
                _href : "${context.request.tenantPrefix}/api/products/${slug}/",
                _links: [
                        self  : new LinkApiObject([href: "${context.request.tenantPrefix}/api/collections/${slug}/"]),
                        images: new LinkApiObject(
                                [href: "${context.request.tenantPrefix}/api/collections/${slug}/images"])
                ]
        ])

        collectionApiObject.withCollection(collection)
        collectionApiObject.withEmbeddedImages(images, collection.featuredImageId, context.request)

        collectionApiObject
    }

}
