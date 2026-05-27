import { drizzle } from 'drizzle-orm/postgres-js'
import postgres from 'postgres'
import * as schema from 'pe-sub-db/src/schema'

const sql = postgres(process.env.DATABASE_URL!)
export const db = drizzle(sql, { schema })
